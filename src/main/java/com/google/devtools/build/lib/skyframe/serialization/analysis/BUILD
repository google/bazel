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

java_library(
    name = "event_listener",
    srcs = ["RemoteAnalysisCachingEventListener.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/skyframe:execution_phase_skykey",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "dependencies_provider",
    srcs = ["RemoteAnalysisCachingDependenciesProvider.java"],
    deps = [
        ":options",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "frontier_violation_checker",
    srcs = ["FrontierViolationChecker.java"],
    deps = [
        ":dependencies_provider",
        ":event_listener",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyfocus_options",
        "//src/main/java/com/google/devtools/build/lib/util:TestType",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
    ],
)

java_library(
    name = "frontier_serializer",
    srcs = [
        "FrontierSerializer.java",
        "SelectedEntrySerializer.java",
    ],
    deps = [
        ":dependencies_provider",
        ":event_listener",
        ":file_dependency_serializer",
        ":file_op_node_map",
        ":invalidation_data_reference_or_future",
        ":long_version_getter_test_injection",
        ":options",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/skyframe:aspect_key_creator",
        "//src/main/java/com/google/devtools/build/lib/skyframe:filesystem_keys",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyframe_cluster",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/util:TestType",
        "//src/main/java/com/google/devtools/build/lib/versioning:long_version_getter",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/main/protobuf:file_invalidation_data_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_library(
    name = "file_op_node_map",
    srcs = ["FileOpNodeMemoizingLookup.java"],
    deps = [
        ":value_or_future_map",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/skyframe:filesystem_keys",
        "//src/main/java/com/google/devtools/build/lib/skyframe:rule_configured_target_value",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "file_dependency_serializer",
    srcs = ["FileDependencySerializer.java"],
    deps = [
        ":file_dependency_key_support",
        ":invalidation_data_reference_or_future",
        ":value_or_future_map",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/skyframe:filesystem_keys",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/util:TestType",
        "//src/main/java/com/google/devtools/build/lib/versioning:long_version_getter",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/protobuf:file_invalidation_data_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_library(
    name = "invalidation_data_reference_or_future",
    srcs = ["InvalidationDataInfoOrFuture.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/concurrent:settable_future_keyed_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:filesystem_keys",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/vfs",
    ],
)

java_library(
    name = "file_dependency_deserializer",
    srcs = [
        "FileDependencies.java",
        "FileDependencyDeserializer.java",
        "FileSystemDependencies.java",
        "ListingDependencies.java",
        "NestedDependencies.java",
    ],
    deps = [
        ":file_dependency_key_support",
        ":value_or_future_map",
        ":versioned_changes",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/concurrent:settable_future_keyed_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/versioning:long_version_getter",
        "//src/main/java/com/google/devtools/build/lib/vfs:ospathpolicy",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:file_invalidation_data_java_proto",
        "//third_party:caffeine",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_library(
    name = "versioned_changes_validator",
    srcs = [
        "FileOpMatchMemoizingLookup.java",
        "FileOpMatchResultTypes.java",
        "NestedMatchMemoizingLookup.java",
        "NestedMatchResultTypes.java",
        "NoMatch.java",
        "VersionedChangesValidator.java",
    ],
    deps = [
        ":file_dependency_deserializer",
        ":value_or_future_map",
        ":versioned_changes",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/concurrent:settable_future_keyed_value",
        "//third_party:guava",
    ],
)

java_library(
    name = "file_dependency_key_support",
    srcs = ["FileDependencyKeySupport.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/versioning:long_version_getter",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:file_invalidation_data_java_proto",
        "//third_party:guava",
    ],
)

java_library(
    name = "options",
    srcs = ["RemoteAnalysisCachingOptions.java"],
    deps = [
        "//src/main/java/com/google/devtools/common/options",
    ],
)

java_library(
    name = "value_or_future_map",
    srcs = [
        "AbstractValueOrFutureMap.java",
        "ValueOrFutureMap.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/concurrent:settable_future_keyed_value",
        "//third_party:guava",
    ],
)

java_library(
    name = "client_id",
    srcs = ["ClientId.java"],
)

java_library(
    name = "versioned_changes",
    srcs = ["VersionedChanges.java"],
    deps = [
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "long_version_getter_test_injection",
    srcs = ["LongVersionGetterTestInjection.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util:TestType",
        "//src/main/java/com/google/devtools/build/lib/versioning:long_version_getter",
        "//third_party:guava",
    ],
)
