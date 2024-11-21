load("@rules_java//java:defs.bzl", "java_library", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["*"]),
    visibility = ["//src:__subpackages__"],
)

java_test(
    name = "BuildOutputFormatterCallbackTest",
    size = "medium",
    srcs = ["BuildOutputFormatterCallbackTest.java"],
    shard_count = 4,
    deps = [
        ":configured_target_query_helper",
        ":configured_target_query_test",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages:label_printer",
        "//src/main/java/com/google/devtools/build/lib/query2",
        "//src/main/java/com/google/devtools/build/lib/query2/common:cquery-node",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//src/main/java/com/google/devtools/build/lib/query2/query/aspectresolvers",
        "//src/main/java/com/google/devtools/build/lib/util:filetype",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "GraphOutputFormatterCallbackTest",
    size = "medium",
    srcs = ["GraphOutputFormatterCallbackTest.java"],
    shard_count = 4,
    deps = [
        ":configured_target_query_helper",
        ":configured_target_query_test",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages:label_printer",
        "//src/main/java/com/google/devtools/build/lib/query2",
        "//src/main/java/com/google/devtools/build/lib/query2/common:cquery-node",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ConfiguredTargetQuerySemanticsTest",
    size = "medium",
    timeout = "long",
    srcs = ["ConfiguredTargetQuerySemanticsTest.java"],
    shard_count = 2,
    deps = [
        ":configured_target_query_test",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/execution_transition_factory",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/fragment_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/transitions/configuration_transition",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/transitions/patch_transition",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/transitions/transition_factory",
        "//src/main/java/com/google/devtools/build/lib/analysis:test/test_configuration",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages:label_printer",
        "//src/main/java/com/google/devtools/build/lib/query2/common:cquery-node",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//src/main/java/com/google/devtools/build/lib/util:filetype",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/query2/testutil",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_library(
    name = "configured_target_query_helper",
    testonly = 1,
    srcs = ["ConfiguredTargetQueryHelper.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/packages:label_printer",
        "//src/main/java/com/google/devtools/build/lib/query2",
        "//src/main/java/com/google/devtools/build/lib/query2/common:cquery-node",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/query2/testutil",
        "//third_party:guava",
    ],
)

java_library(
    name = "configured_target_query_test",
    testonly = 1,
    srcs = ["ConfiguredTargetQueryTest.java"],
    deps = [
        ":configured_target_query_helper",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/query2",
        "//src/main/java/com/google/devtools/build/lib/query2/common:cquery-node",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//src/test/java/com/google/devtools/build/lib/query2/testutil",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ProtoOutputFormatterCallbackTest",
    size = "medium",
    srcs = ["ProtoOutputFormatterCallbackTest.java"],
    shard_count = 4,
    deps = [
        ":configured_target_query_helper",
        ":configured_target_query_test",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/execution_transition_factory",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages:label_printer",
        "//src/main/java/com/google/devtools/build/lib/query2",
        "//src/main/java/com/google/devtools/build/lib/query2/common:cquery-node",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//src/main/java/com/google/devtools/build/lib/query2/query/aspectresolvers",
        "//src/main/java/com/google/devtools/build/lib/util:filetype",
        "//src/main/protobuf:analysis_v2_java_proto",
        "//src/main/protobuf:build_java_proto",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@com_google_protobuf//:protobuf_java",
        "@com_google_protobuf//:protobuf_java_util",
    ],
)

java_test(
    name = "TransitionsOutputFormatterTest",
    size = "medium",
    srcs = ["TransitionsOutputFormatterTest.java"],
    shard_count = 4,
    deps = [
        ":configured_target_query_helper",
        ":configured_target_query_test",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/transitions/configuration_transition",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/transitions/no_transition",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/transitions/transition_factory",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages:label_printer",
        "//src/main/java/com/google/devtools/build/lib/query2",
        "//src/main/java/com/google/devtools/build/lib/query2/common:cquery-node",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//src/main/java/com/google/devtools/build/lib/util:filetype",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/query2/testutil",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "FilesOutputFormatterCallbackTest",
    srcs = ["FilesOutputFormatterCallbackTest.java"],
    shard_count = 4,
    deps = [
        ":configured_target_query_helper",
        ":configured_target_query_test",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:top_level_artifact_context",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/query2",
        "//src/main/java/com/google/devtools/build/lib/query2/common:cquery-node",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)
