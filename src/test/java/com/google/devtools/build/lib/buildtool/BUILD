load("@bazel_skylib//rules:build_test.bzl", "build_test")
load("@rules_java//java:defs.bzl", "java_library", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_testonly = 1,
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["*"]) + [
        "//src/test/java/com/google/devtools/build/lib/buildtool/util:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

java_test(
    name = "JobsConverterTest",
    srcs = [
        "JobsConverterTest.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:build-request-options",
        "//src/main/java/com/google/devtools/build/lib/actions:localhost_capacity",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "SymlinkForestTest",
    srcs = [
        "SymlinkForestTest.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:buildtool/symlink_forest",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

test_suite(
    name = "BuildtoolTests",
    tests = [
        ":JobsConverterTest",
        ":SymlinkForestTest",
    ],
)

java_test(
    name = "ActionListenerIntegrationTest",
    srcs = ["ActionListenerIntegrationTest.java"],
    tags = [
        "manual",
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:extra_action_artifacts_provider",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/includescanning",
        "//src/main/java/com/google/devtools/build/lib/rules/java:java-compilation",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:extra_actions_base_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "CompileAfterOptionChangeTest",
    srcs = ["CompileAfterOptionChangeTest.java"],
    tags = [
        "manual",
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "CompileOneDependencyIntegrationTest",
    srcs = ["CompileOneDependencyIntegrationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/rules/python:PythonTestUtils",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ContextProviderInitializationTest",
    srcs = ["ContextProviderInitializationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/exec:executor_builder",
        "//src/main/java/com/google/devtools/build/lib/exec:executor_lifecycle_listener",
        "//src/main/java/com/google/devtools/build/lib/skyframe:ephemeral_check_if_output_consumed",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
    ],
)

java_test(
    name = "CorruptedActionCacheTest",
    srcs = ["CorruptedActionCacheTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "DanglingSymlinkTest",
    srcs = ["DanglingSymlinkTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "UnusedInputsFailureIntegrationTest",
    srcs = ["UnusedInputsFailureIntegrationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/skyframe:detailed_exceptions",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)

java_test(
    name = "DirectoryArtifactWarningTest",
    srcs = ["DirectoryArtifactWarningTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
    ],
)

java_test(
    name = "EditDuringBuildTest",
    srcs = ["EditDuringBuildTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "EnvironmentRestrictedBuildTest",
    srcs = ["EnvironmentRestrictedBuildTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)

java_test(
    name = "GenQueryIntegrationTest",
    srcs = ["GenQueryIntegrationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/main/java/com/google/devtools/build/lib/skyframe:transitive_target_key",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@com_google_protobuf//:protobuf_java",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)

java_test(
    name = "IncludeValidationTest",
    srcs = ["IncludeValidationTest.java"],
    tags = [
        "manual",
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
    ],
)

java_test(
    name = "InconsistentFilesystemTest",
    srcs = ["InconsistentFilesystemTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/unix",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
    ],
)

java_test(
    name = "InstrumentationFilterSupportTest",
    srcs = [
        "InstrumentationFilterSupportTest.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "KeepGoingTest",
    srcs = ["KeepGoingTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "LabelCrossesPackageBoundaryTest",
    srcs = ["LabelCrossesPackageBoundaryTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
    ],
)

java_test(
    name = "MiscAnalysisTest",
    srcs = ["MiscAnalysisTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:JunitUtils",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:guava",
        "//third_party:guava-testlib",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "MissingInputActionTest",
    srcs = ["MissingInputActionTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/bazel:modules",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "NoOutputActionTest",
    srcs = ["NoOutputActionTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "OutputArtifactConflictTest",
    srcs = ["OutputArtifactConflictTest.java"],
    shard_count = 3,
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:JunitUtils",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)

java_test(
    name = "PackageGroupIntegrationTest",
    srcs = ["PackageGroupIntegrationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/rules/python:PythonTestUtils",
        "//third_party:junit4",
    ],
)

java_test(
    name = "PathPrettyPrinterTest",
    srcs = ["PathPrettyPrinterTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ProgressReportingTest",
    srcs = ["ProgressReportingTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/unix",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "QueryIntegrationTest",
    srcs = ["QueryIntegrationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib:runtime/blaze_command_result",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/query2/query/output",
        "//src/main/java/com/google/devtools/build/lib/runtime/commands",
        "//src/main/java/com/google/devtools/build/lib/skyframe:default_syscall_cache",
        "//src/main/java/com/google/devtools/build/lib/skyframe:sky_functions",
        "//src/main/java/com/google/devtools/build/lib/unix",
        "//src/main/java/com/google/devtools/build/lib/util:exit_code",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:build_java_proto",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/main/protobuf:invocation_policy_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestUtils",
        "//src/test/java/com/google/devtools/build/skyframe:testutil",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:truth",
        "@com_google_protobuf//:protobuf_java",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)

java_test(
    name = "SymlinkDependencyAnalysisTest",
    srcs = ["SymlinkDependencyAnalysisTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_library(
    name = "TransitiveDataDependencyTest",
    srcs = ["TransitiveDataDependencyTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "TargetCompleteEventTest",
    srcs = ["TargetCompleteEventTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/authandtls/credentialhelper:credential_module",
        "//src/main/java/com/google/devtools/build/lib/buildeventservice",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "TargetSummaryEventTest",
    srcs = ["TargetSummaryEventTest.java"],
    data = ["//src/test/java/com/google/devtools/build/lib:embedded_scripts"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/authandtls/credentialhelper:credential_module",
        "//src/main/java/com/google/devtools/build/lib/buildeventservice",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "BuildResultTestCase",
    srcs = ["BuildResultTestCase.java"],
    shard_count = 4,
    tags = [
        "manual",
        "no_windows",
    ],
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = ["//src/test/java/com/google/devtools/build/lib:test_runner"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "AqueryBuildToolTest",
    srcs = ["AqueryBuildToolTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib:runtime/blaze_command_result",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/query2",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//src/main/java/com/google/devtools/build/lib/runtime/commands",
        "//src/main/protobuf:analysis_v2_java_proto",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@com_google_protobuf//:protobuf_java",
    ],
)

# TODO(b/19011399): Remove any manual tags used to disable the tests on bazel,
#  and enable manual tests inside Google if appropriate.
# Keep in sync with Copybara transformation that makes these tests manual.

build_test(
    name = "all_manual_tests",
    targets = [
        ":ActionListenerIntegrationTest",
        ":BuildResultTestCase",
        ":CompileAfterOptionChangeTest",
        ":IncludeValidationTest",
        # Disabled in Google.
        "//src/test/java/com/google/devtools/build/lib/bazel/rules/python:PythonTests",
        # Disabled in Google.
        "//src/test/java/com/google/devtools/build/lib/bazel/rules/sh:ShTests",
        # Disabled in Google.
        "//src/test/java/com/google/devtools/build/lib/packages:BazelDocumentationTests",
    ],
)

java_test(
    name = "ShutdownCommandTest",
    srcs = ["ShutdownCommandTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime/blaze_command_result",
        "//src/main/java/com/google/devtools/build/lib/runtime/commands",
        "//src/main/java/com/google/devtools/build/lib/util:exit_code",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "SubcommandEventTest",
    srcs = ["SubcommandEventTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "SkymeldBuildIntegrationTest",
    srcs = ["SkymeldBuildIntegrationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_phase_complete_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skymeld_module",
        "//src/main/java/com/google/devtools/build/lib/skyframe:top_level_status_events",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)

java_test(
    name = "SkymeldOutputServiceBuildIntegrationTest",
    srcs = ["SkymeldOutputServiceBuildIntegrationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:output_service",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "BuildResultListenerIntegrationTest",
    srcs = ["BuildResultListenerIntegrationTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:junit4",
        "//third_party:truth",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)

java_test(
    name = "ConvenienceSymlinkTest",
    srcs = [
        "ConvenienceSymlinkTest.java",
    ],
    tags = ["no_windows"],  # Creates symbolic links.
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/analysis:actions/file_write_action_context",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/fragment",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/fragment_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/transitions/configuration_transition",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/transitions/patch_transition",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/transitions/transition_factory",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/exec:file_write_strategy",
        "//src/main/java/com/google/devtools/build/lib/exec:module_action_context_registry",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)

java_test(
    name = "SkyfocusIntegrationTest",
    srcs = ["SkyfocusIntegrationTest.java"],
    shard_count = 2,
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/runtime/commands",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyfocus_state",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/skyframe:testutil",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ProjectResolutionTest",
    srcs = ["ProjectResolutionTest.java"],
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:project_resolution_exception",
        "//src/main/java/com/google/devtools/build/lib/analysis:projects",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe:project_files_lookup_function",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)
