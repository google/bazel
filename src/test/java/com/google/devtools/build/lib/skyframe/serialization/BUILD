load("@rules_java//java:defs.bzl", "java_library", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

java_test(
    name = "PackageLookupValueCodecTest",
    size = "small",
    srcs = ["PackageLookupValueCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/skyframe:package_lookup_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:junit4",
    ],
)

java_test(
    name = "MutableListCodecTest",
    size = "small",
    srcs = ["MutableListCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:junit4",
    ],
)

java_test(
    name = "SingletonCodecTest",
    size = "small",
    srcs = ["SingletonCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//third_party:junit4",
    ],
)

java_test(
    name = "TupleCodecTest",
    size = "small",
    srcs = ["TupleCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:junit4",
    ],
)

java_test(
    name = "TransitiveTraversalValueCodecTest",
    size = "small",
    srcs = ["TransitiveTraversalValueCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:transitive_info_provider",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/skyframe:transitive_traversal_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:mockito",
    ],
)

java_test(
    name = "WorkspaceNameValueCodecTest",
    size = "small",
    srcs = ["WorkspaceNameValueCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe:workspace_name_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//third_party:junit4",
    ],
)

java_test(
    name = "MemoizerTest",
    size = "small",
    srcs = ["MemoizerTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils:round-tripping",
        "//src/main/java/com/google/devtools/build/lib/util:hash_codes",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:truth",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_test(
    name = "RecursivePkgKeyCodecTest",
    size = "small",
    srcs = ["RecursivePkgKeyCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe:recursive_pkg_key",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//third_party:guava",
        "//third_party:junit4",
    ],
)

java_test(
    name = "RootedPathCodecTest",
    size = "small",
    srcs = ["RootedPathCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:junit4",
    ],
)

java_test(
    name = "IgnoredSubdirectoriesValueCodecTest",
    size = "small",
    srcs = ["IgnoredSubdirectoriesValueCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe:ignored_subdirectories_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
        "//third_party:junit4",
    ],
)

java_test(
    name = "LongCodecTest",
    size = "small",
    srcs = ["LongCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//third_party:junit4",
    ],
)

java_test(
    name = "StarlarkInfoCodecTest",
    size = "small",
    srcs = ["StarlarkInfoCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/skyframe:bzl_load_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "StatementCodecTest",
    size = "small",
    srcs = ["StatementCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ByteArrayCodecTest",
    size = "small",
    srcs = ["ByteArrayCodecTest.java"],
    javacopts = [
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//third_party:junit4",
    ],
)

java_test(
    name = "IntegerCodecTest",
    size = "small",
    srcs = ["IntegerCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//third_party:junit4",
    ],
)

java_test(
    name = "NullableListCodecTest",
    size = "small",
    srcs = ["NullableListCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//third_party:guava",
        "//third_party:junit4",
    ],
)

java_test(
    name = "BooleanCodecTest",
    size = "small",
    srcs = ["BooleanCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//third_party:junit4",
    ],
)

java_test(
    name = "ExpressionCodecTest",
    size = "small",
    srcs = ["ExpressionCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "FileSymlinkExceptionCodecTest",
    size = "small",
    srcs = ["FileSymlinkExceptionCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_cycle_exception",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_exception",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_infinite_expansion_exception",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "NoSuchTargetExceptionCodecTest",
    size = "small",
    srcs = ["NoSuchTargetExceptionCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:truth",
    ],
)

java_test(
    name = "DirectoryListingValueCodecTest",
    size = "small",
    srcs = ["DirectoryListingValueCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe:directory_listing_state_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:directory_listing_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
        "//third_party:junit4",
    ],
)

java_test(
    name = "DirectoryListingStateValueCodecTest",
    size = "small",
    srcs = ["DirectoryListingStateValueCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe:directory_listing_state_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:guava",
        "//third_party:junit4",
    ],
)

java_test(
    name = "DictCodecTest",
    size = "small",
    srcs = ["DictCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ModuleCodecTest",
    size = "small",
    srcs = ["ModuleCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/skyframe:bzl_load_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyframe_cluster",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:module_codec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils:round-tripping",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/eval",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "NoSuchPackageExceptionCodecTest",
    size = "small",
    srcs = ["NoSuchPackageExceptionCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "BzlLoadValueCodecTest",
    size = "small",
    srcs = ["BzlLoadValueCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages:bzl_visibility",
        "//src/main/java/com/google/devtools/build/lib/skyframe:bzl_load_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils:round-tripping",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ProviderCodecTest",
    size = "small",
    srcs = ["ProviderCodecTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/skyframe:bzl_load_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:junit4",
    ],
)

java_test(
    name = "ProfileCollectorTest",
    size = "small",
    srcs = ["ProfileCollectorTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "//third_party/pprof:profile_java_proto",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_library(
    name = "serialization-tests",
    testonly = 1,
    srcs = glob(
        ["*.java"],
        exclude = [
            "PackageLookupValueCodecTest.java",
            "MutableListCodecTest.java",
            "SingletonCodecTest.java",
            "TupleCodecTest.java",
            "TransitiveTraversalValueCodecTest.java",
            "WorkspaceNameValueCodecTest.java",
            "MemoizerTest.java",
            "FileSymlinkExceptionCodecTest.java",
            "DirectoryListingValueCodecTest.java",
            "NoSuchTargetExceptionCodecTest.java",
            "NoSuchPackageExceptionCodecTest.java",
            "ModuleCodecTest.java",
            "DictCodecTest.java",
            "DirectoryListingStateValueCodecTest.java",
            "ProfileCollectorTest.java",
            "RecursivePkgKeyCodecTest.java",
            "RootedPathCodecTest.java",
            "IgnoredSubdirectoriesValueCodecTest.java",
            "LongCodecTest.java",
            "StarlarkInfoCodecTest.java",
            "StatementCodecTest.java",
            "ByteArrayCodecTest.java",
            "IntegerCodecTest.java",
            "NullableListCodecTest.java",
            "BooleanCodecTest.java",
            "ExpressionCodecTest.java",
            "BzlLoadValueCodecTest.java",
            "ProviderCodecTest.java",
        ],
    ),
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/skyframe:precomputed_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:client_id",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils:dumper",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils:fake_invalidation_data_helper",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils:round-tripping",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/protobuf:build_java_proto",
        "//third_party:auto_value",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:truth",
        "@com_google_protobuf//:protobuf_java",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)

java_test(
    name = "SerializationTests",
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = [
        ":serialization-tests",
        "//src/test/java/com/google/devtools/build/lib:test_runner",
    ],
)
