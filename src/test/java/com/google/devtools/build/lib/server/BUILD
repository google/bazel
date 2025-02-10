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

java_library(
    name = "ServerTests_lib",
    srcs = glob(["*.java"]),
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime/blaze_command_result",
        "//src/main/java/com/google/devtools/build/lib:runtime/command_dispatcher",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/server",
        "//src/main/java/com/google/devtools/build/lib/server:idle_task",
        "//src/main/java/com/google/devtools/build/lib/server:install_base_garbage_collector",
        "//src/main/java/com/google/devtools/build/lib/server:pid_file_watcher",
        "//src/main/java/com/google/devtools/build/lib/server:shutdown_hooks",
        "//src/main/java/com/google/devtools/build/lib/unix:procmeminfo_parser",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/util/io:io-proto",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs",
        "//src/main/protobuf:command_server_java_grpc",
        "//src/main/protobuf:command_server_java_proto",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/main/protobuf:invocation_policy_java_proto",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestThread",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestUtils",
        "//src/test/java/com/google/devtools/build/lib/testutil:external_file_system_lock",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:truth",
        "//third_party/grpc-java:grpc-jar",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_test(
    name = "ServerTests",
    size = "medium",
    shard_count = 3,
    tags = ["requires-network"],  # https://github.com/bazelbuild/bazel/issues/20743
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = [
        ":ServerTests_lib",
        "//src/test/java/com/google/devtools/build/lib:test_runner",
    ],
)
