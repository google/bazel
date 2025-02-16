load("@rules_java//java:defs.bzl", "java_library", "java_test")

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
    name = "testutil",
    testonly = 1,
    srcs = [
        "SandboxedSpawnRunnerTestCase.java",
        "SpawnRunnerTestUtil.java",
    ],
    runtime_deps = [
        "//src/test/shell/integration:spend_cpu_time",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifact_expander",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_input_expander",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_runner",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:spawn_java_proto",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:mockito",
    ],
)

java_test(
    name = "SmallTests",
    size = "small",
    srcs =
        [
            "AbstractContainerizingSandboxedSpawnTest.java",
            "SandboxHelpersTest.java",
            "SandboxOptionsTest.java",
            "SymlinkedSandboxedSpawnTest.java",
        ],
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = [
        "//src/test/java/com/google/devtools/build/lib:test_runner",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/exec:bin_tools",
        "//src/main/java/com/google/devtools/build/lib/exec:tree_deleter",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandbox_helpers",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandbox_options",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandboxed_spawns",
        "//src/main/java/com/google/devtools/build/lib/sandbox:tree_deleter",
        "//src/main/java/com/google/devtools/build/lib/skyframe:tree_artifact_value",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs",
        "//src/main/java/com/google/devtools/common/options",
        "//src/test/java/com/google/devtools/build/lib/actions/util",
        "//src/test/java/com/google/devtools/build/lib/exec/util",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestUtils",
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:truth",
    ],
)

java_test(
    name = "SpawnRunnerTests",
    size = "medium",
    srcs = [
        "DarwinSandboxedSpawnRunnerTest.java",
        "LinuxSandboxCommandLineBuilderTest.java",
        "LinuxSandboxedSpawnRunnerTest.java",
        "ProcessWrapperSandboxedSpawnRunnerTest.java",
    ],
    data = ["//src/test/java/com/google/devtools/build/lib:embedded_scripts"],
    tags = ["no_windows"],
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = [
        "//src/test/java/com/google/devtools/build/lib:test_runner",
    ],
    deps = [
        ":testutil",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:execution_requirements",
        "//src/main/java/com/google/devtools/build/lib/actions:localhost_capacity",
        "//src/main/java/com/google/devtools/build/lib/exec:bin_tools",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_runner",
        "//src/main/java/com/google/devtools/build/lib/exec:tree_deleter",
        "//src/main/java/com/google/devtools/build/lib/sandbox:darwin_sandbox",
        "//src/main/java/com/google/devtools/build/lib/sandbox:linux_sandbox",
        "//src/main/java/com/google/devtools/build/lib/sandbox:linux_sandbox_command_line_builder",
        "//src/main/java/com/google/devtools/build/lib/sandbox:process_wrapper_sandbox",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandbox_helpers",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandboxed_spawns",
        "//src/main/java/com/google/devtools/build/lib/sandbox:tree_deleter",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs",
        "//src/test/java/com/google/devtools/build/lib/actions/util",
        "//src/test/java/com/google/devtools/build/lib/exec/util",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)

java_test(
    name = "CgroupsInfoTest",
    size = "small",
    srcs = ["CgroupsInfoTest.java"],
    tags = [
        # MacOS does not have cgroups so it can't support hardened sandbox
        "no_macos",
        "no_windows",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/sandbox:cgroups_info",
        "//src/test/java/com/google/devtools/build/lib/vfs/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)
