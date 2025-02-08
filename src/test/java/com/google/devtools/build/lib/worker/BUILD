load("@rules_java//java:defs.bzl", "java_binary", "java_library", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_testonly = 1,
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["*"]),
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "ExampleWorker_lib",
    srcs = [
        "ExampleWorker.java",
        "ExampleWorkerMultiplexer.java",
        "ExampleWorkerMultiplexerOptions.java",
        "ExampleWorkerOptions.java",
        "ExampleWorkerProtocol.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:execution_requirements",
        "//src/main/java/com/google/devtools/build/lib/worker:work_request_handlers",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:worker_protocol_java_proto",
        "//third_party:gson",
        "//third_party:guava",
    ],
)

java_library(
    name = "testutil",
    testonly = 1,
    srcs = [
        "SandboxHelper.java",
        "WorkerTestUtils.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:execution_requirements",
        "//src/main/java/com/google/devtools/build/lib/actions:localhost_capacity",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandbox_helpers",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/worker",
        "//src/main/java/com/google/devtools/build/lib/worker:singleplex_worker",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_key",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_options",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_pool",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_spawn_runner",
        "//src/test/java/com/google/devtools/build/lib/actions/util",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
    ],
)

java_binary(
    name = "ExampleWorker",
    main_class = "com.google.devtools.build.lib.worker.ExampleWorker",
    runtime_deps = [":ExampleWorker_lib"],
)

java_binary(
    name = "ExampleWorkerMultiplexer",
    main_class = "com.google.devtools.build.lib.worker.ExampleWorkerMultiplexer",
    runtime_deps = [":ExampleWorker_lib"],
)

java_library(
    name = "WorkerTests_lib",
    srcs = glob(
        ["*Test.java"],
        exclude = ["WorkRequestHandlerTest.java"],
    ),
    deps = [
        ":testutil",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_input_helper",
        "//src/main/java/com/google/devtools/build/lib/actions:artifact_expander",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:execution_requirements",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:localhost_capacity",
        "//src/main/java/com/google/devtools/build/lib/actions:resource_manager",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_tree",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/analysis:server_directories",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/exec:bin_tools",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_runner",
        "//src/main/java/com/google/devtools/build/lib/exec/local",
        "//src/main/java/com/google/devtools/build/lib/metrics:resource_collector",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandbox_helpers",
        "//src/main/java/com/google/devtools/build/lib/sandbox:tree_deleter",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/util:resource_converter",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs",
        "//src/main/java/com/google/devtools/build/lib/worker",
        "//src/main/java/com/google/devtools/build/lib/worker:error_message",
        "//src/main/java/com/google/devtools/build/lib/worker:multiplex_worker",
        "//src/main/java/com/google/devtools/build/lib/worker:recording_input_stream",
        "//src/main/java/com/google/devtools/build/lib/worker:singleplex_worker",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_exec_root",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_factory",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_files_hash",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_key",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_module",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_options",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_pool",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_pool_config",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_pool_impl",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_process_metrics",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_process_status",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_spawn_runner",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:worker_protocol_java_proto",
        "//src/test/java/com/google/devtools/build/lib/actions/util",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/exec/util",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestThread",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestUtils",
        "//src/test/java/com/google/devtools/build/lib/vfs/util",
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
    name = "WorkerTests",
    size = "small",
    data = [
        "//src/test/java/com/google/devtools/build/lib:embedded_scripts",
    ],
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = [
        ":WorkerTests_lib",
        "//src/test/java/com/google/devtools/build/lib:test_runner",
    ],
)

java_test(
    name = "WorkRequestHandlerTest",
    srcs = ["WorkRequestHandlerTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/worker:work_request_handlers",
        "//src/main/protobuf:worker_protocol_java_proto",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:truth",
    ],
)
