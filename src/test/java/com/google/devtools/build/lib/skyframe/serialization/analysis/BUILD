load("@rules_java//java:defs.bzl", "java_library", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

java_library(
    name = "FrontierSerializerTestBase",
    testonly = True,
    srcs = ["FrontierSerializerTestBase.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/runtime/commands",
        "//src/main/java/com/google/devtools/build/lib/skyframe:aspect_key_creator",
        "//src/main/java/com/google/devtools/build/lib/skyframe:configured_target_key",
        "//src/main/java/com/google/devtools/build/lib/skyframe:rule_configured_target_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:sky_functions",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:frontier_serializer",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:truth",
        "//third_party/pprof:profile_java_proto",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_test(
    name = "FrontierSerializerTest",
    srcs = ["FrontierSerializerTest.java"],
    tags = ["no_windows"],  # b/380393822 - disable until PathFragmentPrefixTrie handles windows correctly.
    deps = [
        ":FrontierSerializerTestBase",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/skyframe:sky_functions",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:serialization_module",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:long_version_getter_test_injection",
        "//src/main/java/com/google/devtools/build/lib/versioning:long_version_getter",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:truth",
    ],
)

java_test(
    name = "ValueOrFutureMapTest",
    srcs = ["ValueOrFutureMapTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/concurrent:settable_future_keyed_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:value_or_future_map",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "FileOpNodeMemoizingLookupTest",
    srcs = ["FileOpNodeMemoizingLookupTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/skyframe:filesystem_keys",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:file_op_node_map",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "VersionedChangesTest",
    srcs = ["VersionedChangesTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:versioned_changes",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "FileSystemDependenciesTest",
    srcs = ["FileSystemDependenciesTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:file_dependency_deserializer",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:versioned_changes",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "FileOpMatchMemoizingLookupTest",
    srcs = ["FileOpMatchMemoizingLookupTest.java"],
    deps = [
        ":controllable_file_dependencies",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:file_dependency_deserializer",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:versioned_changes",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:versioned_changes_validator",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)

java_test(
    name = "NestedMatchMemoizingLookupTest",
    srcs = ["NestedMatchMemoizingLookupTest.java"],
    deps = [
        ":controllable_file_dependencies",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:file_dependency_deserializer",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:versioned_changes",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:versioned_changes_validator",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)

java_test(
    name = "VersionedChangesValidatorTest",
    srcs = ["VersionedChangesValidatorTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:file_dependency_deserializer",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:versioned_changes",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:versioned_changes_validator",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)

java_library(
    name = "controllable_file_dependencies",
    srcs = ["ControllableFileDependencies.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:file_dependency_deserializer",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:versioned_changes",
        "//third_party:guava",
    ],
)
