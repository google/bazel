load("@rules_java//java:defs.bzl", "java_library", "java_test")

package(
    default_testonly = 1,
    default_visibility = [
        "//src/test/java/com/google/devtools/build/lib:__subpackages__",
    ],
)

licenses(["notice"])  # Apache 2.0

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(
        ["**"],
        exclude = [
            "*~",
        ],
    ),
    visibility = ["//src:__pkg__"],
)

java_library(
    name = "ShellTests_lib",
    srcs = [
        "CommandLargeInputsTest.java",
        "CommandTest.java",
        "CommandUsingProcessWrapperTest.java",
        "ExecutionStatisticsTestUtil.java",
        "TestUtil.java",
    ],
    javacopts = ["-Xlint:-deprecation"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions:action_input_helper",
        "//src/main/java/com/google/devtools/build/lib/bazel:main",
        "//src/main/java/com/google/devtools/build/lib/collect",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/unix",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:execution_statistics_java_proto",
        "//src/test/java/com/google/devtools/build/lib:test_runner",
        "//src/test/java/com/google/devtools/build/lib/events:testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestUtils",
        "//src/test/java/com/google/devtools/build/lib/vfs/util",
        "//third_party:guava",
        "//third_party:guava-testlib",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:truth",
    ],
)

java_test(
    name = "ShellTests",
    size = "medium",
    data = [
        ":killmyself",
        "//src/main/tools:process-wrapper",
        "//src/test/shell/integration:spend_cpu_time",
    ],
    flaky = 1,
    shard_count = 4,
    tags = [
        "no_windows",
        "shell",
    ],
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = [
        ":ShellTests_lib",
        "//src/test/java/com/google/devtools/build/lib:test_runner",
    ],
)

java_test(
    name = "ShellUtilsTest",
    srcs = ["ShellUtilsTest.java"],
    tags = [
        "shell",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ShellUtilsWithBashTest",
    srcs = ["ShellUtilsWithBashTest.java"],
    tags = [
        # TODO(laszlocsomor): remove the "no_windows" tag and merge "ShellUtilsWithBashTest" into
        # "ShellUtilsTest" after Bazel has a way to tell the test about the shell interpreter's
        # path, and therefore ShellUtilsWithBashTest can be made to run on Windows.  A possible
        # approach is to update the sh_toolchain rule in //tools/sh:sh_toolchain.bzl to return a
        # platform_common.TemplateVariableInfo provider, then add "toolchains" attribute to the
        # java_test, and pass the shell's path from a Make Variable using the "args" attribute.
        "no_windows",
        "shell",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "CommandResultTest",
    srcs = ["CommandResultTest.java"],
    tags = [
        "shell",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ConsumersTest",
    srcs = ["ConsumersTest.java"],
    tags = [
        "shell",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ExecutionStatisticsTest",
    srcs = ["ExecutionStatisticsTest.java"],
    tags = [
        "shell",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs",
        "//src/main/protobuf:execution_statistics_java_proto",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestUtils",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "FutureConsumptionTest",
    srcs = ["FutureConsumptionTest.java"],
    tags = [
        "shell",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "InterruptibleTest",
    size = "small",
    srcs = ["InterruptibleTest.java"],
    tags = [
        "no_windows",
        "shell",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "LoadTest",
    srcs = ["LoadTest.java"],
    data = [":cat_file"],
    tags = [
        "shell",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//third_party:junit4",
        "@rules_java//java/runfiles",
    ],
)

cc_binary(
    name = "cat_file",
    testonly = 1,
    srcs = ["cat_file.cc"],
    visibility = ["//visibility:private"],
)

java_test(
    name = "TerminationStatusTest",
    srcs = ["TerminationStatusTest.java"],
    tags = [
        "shell",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ToTruncatedStringTest",
    srcs = ["ToTruncatedStringTest.java"],
    tags = [
        "shell",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "CommandUsingLinuxSandboxTest",
    timeout = "moderate",
    srcs = [
        "CommandUsingLinuxSandboxTest.java",
        "ExecutionStatisticsTestUtil.java",
    ],
    data = [
        "//src/main/tools:linux-sandbox",
        "//src/test/shell/integration:spend_cpu_time",
    ],
    tags = [
        "no_windows",
        "shell",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/bazel:main",
        "//src/main/java/com/google/devtools/build/lib/collect",
        "//src/main/java/com/google/devtools/build/lib/sandbox:linux_sandbox_command_line_builder",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/unix",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/protobuf:execution_statistics_java_proto",
        "//src/test/java/com/google/devtools/build/lib:test_runner",
        "//src/test/java/com/google/devtools/build/lib/events:testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestUtils",
        "//src/test/java/com/google/devtools/build/lib/vfs/util",
        "//third_party:guava",
        "//third_party:guava-testlib",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:truth",
    ],
)

cc_binary(
    name = "killmyself",
    srcs = ["killmyself.cc"],
)
