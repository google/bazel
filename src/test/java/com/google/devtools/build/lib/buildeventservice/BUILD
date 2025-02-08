load("@rules_java//java:defs.bzl", "java_library", "java_test")

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

ABSTRACT_TEST_SRCS = [
    "AbstractBuildEventRecorder.java",
    "AbstractBuildEventServiceTransportTest.java",
]

java_library(
    name = "AbstractBuildEventServiceTransportTest",
    srcs = ABSTRACT_TEST_SRCS,
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/buildeventservice",
        "//src/main/java/com/google/devtools/build/lib/buildeventservice:buildeventservice-options",
        "//src/main/java/com/google/devtools/build/lib/buildeventservice/client",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:pair",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:netty",
        "//third_party:truth",
        "//third_party/grpc-java:grpc-jar",
        "@com_google_protobuf//:protobuf_java",
        "@com_google_protobuf//:protobuf_java_util",
        "@googleapis//google/devtools/build/v1:build_java_proto",
    ],
)

java_test(
    name = "BuildEventTransportTest",
    srcs = [
        "BuildEventServiceGrpcClientTest.java",
        "BuildEventServiceProtoUtilTest.java",
    ],
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = ["//src/test/java/com/google/devtools/build/lib:test_runner"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/buildeventservice",
        "//src/main/java/com/google/devtools/build/lib/buildeventservice/client",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "//third_party/grpc-java:grpc-jar",
        "@com_google_protobuf//:protobuf_java",
        "@com_google_protobuf//:protobuf_java_util",
        "@googleapis//google/devtools/build/v1:build_java_grpc",
        "@googleapis//google/devtools/build/v1:build_java_proto",
    ],
)

java_test(
    name = "BuildEventServiceTransportGrpcTest",
    srcs = ["BuildEventServiceTransportGrpcTest.java"],
    deps = [
        ":AbstractBuildEventServiceTransportTest",
        "//src/main/java/com/google/devtools/build/lib/buildeventservice/client",
        "//src/main/java/com/google/devtools/build/lib/util:pair",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/test/java/com/google/devtools/build/lib/remote/util:free_port_finder",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party/grpc-java:grpc-jar",
        "@com_google_protobuf//:protobuf_java",
        "@googleapis//google/devtools/build/v1:build_java_grpc",
        "@googleapis//google/devtools/build/v1:build_java_proto",
    ],
)

java_test(
    name = "BazelBuildEventServiceModuleTest",
    srcs = ["BazelBuildEventServiceModuleTest.java"],
    shard_count = 10,
    tags = [
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/authandtls",
        "//src/main/java/com/google/devtools/build/lib/authandtls/credentialhelper:credential_module",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/buildeventservice",
        "//src/main/java/com/google/devtools/build/lib/buildeventservice:buildeventservice-options",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/transports",
        "//src/main/java/com/google/devtools/build/lib/network:connectivity_status",
        "//src/main/java/com/google/devtools/build/lib/network:noop_connectivity",
        "//src/main/java/com/google/devtools/build/lib/remote/util",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:exit_code",
        "//src/main/protobuf:command_line_java_proto",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//src/test/java/com/google/devtools/build/skyframe:testutil",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:truth",
        "//third_party/grpc-java:grpc-jar",
        "@com_google_protobuf//:protobuf_java",
        "@googleapis//google/devtools/build/v1:build_java_grpc",
        "@googleapis//google/devtools/build/v1:build_java_proto",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_java_proto",
    ],
)
