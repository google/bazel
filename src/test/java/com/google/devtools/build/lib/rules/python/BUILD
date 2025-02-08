load("@rules_java//java:defs.bzl", "java_library", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_testonly = True,
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["*"]),
    visibility = ["//src:__subpackages__"],
)

test_suite(
    name = "PythonTests",
    tests = [
        ":PyBinaryConfiguredTargetTest",
        ":PyInfoTest",
        ":PyLibraryConfiguredTargetTest",
        ":PyRuntimeConfiguredTargetTest",
        ":PyRuntimeInfoTest",
        ":PyTestConfiguredTargetTest",
        ":PythonConfigurationTest",
        ":PythonStarlarkApiTest",
        ":PythonToolchainTest",
        ":PythonVersionSelectTest",
        ":PythonVersionTest",
    ],
)

java_library(
    name = "PythonTestUtils",
    srcs = [
        "PyInfo.java",
        "PyRuntimeInfo.java",
        "PythonTestUtils.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/rules/python",
        "//src/main/java/com/google/devtools/build/lib/skyframe:bzl_load_value",
        "//src/main/java/net/starlark/java/eval",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
    ],
)

java_test(
    name = "PythonConfigurationTest",
    srcs = ["PythonConfigurationTest.java"],
    deps = [
        ":PythonTestUtils",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_options",
        "//src/main/java/com/google/devtools/build/lib/rules/python",
        "//src/main/java/com/google/devtools/common/options",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "PyRuntimeConfiguredTargetTest",
    srcs = ["PyRuntimeConfiguredTargetTest.java"],
    deps = [
        ":PythonTestUtils",
        "//src/main/java/com/google/devtools/build/lib/rules/python",
        "//src/test/java/com/google/devtools/build/lib/actions/util",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_library(
    name = "PyBaseTestBase",
    srcs = ["PyBaseConfiguredTargetTestBase.java"],
    deps = [
        ":PythonTestUtils",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/rules/python",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_library(
    name = "PyExecutableTestBase",
    srcs = ["PyExecutableConfiguredTargetTestBase.java"],
    deps = [
        ":PyBaseTestBase",
        ":PythonTestUtils",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/rules/python",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "PyBinaryConfiguredTargetTest",
    srcs = ["PyBinaryConfiguredTargetTest.java"],
    tags = ["no_windows"],
    deps = [
        ":PyExecutableTestBase",
        ":PythonTestUtils",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "PyTestConfiguredTargetTest",
    srcs = ["PyTestConfiguredTargetTest.java"],
    deps = [
        ":PyExecutableTestBase",
        ":PythonTestUtils",
        "//src/main/java/com/google/devtools/build/lib/actions:execution_requirements",
        "//src/main/java/com/google/devtools/build/lib/analysis:test/execution_info",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "PyLibraryConfiguredTargetTest",
    srcs = ["PyLibraryConfiguredTargetTest.java"],
    deps = [
        ":PyBaseTestBase",
        ":PythonTestUtils",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/rules/python",
        "//src/test/java/com/google/devtools/build/lib/actions/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "PythonVersionTest",
    srcs = ["PythonVersionTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/rules/python",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "PythonVersionSelectTest",
    srcs = ["PythonVersionSelectTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:file_provider",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "PythonToolchainTest",
    srcs = ["PythonToolchainTest.java"],
    deps = [
        ":PythonTestUtils",
        "//src/main/java/com/google/devtools/build/lib/analysis/platform",
        "//src/main/java/net/starlark/java/eval",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "PyInfoTest",
    srcs = ["PyInfoTest.java"],
    deps = [
        ":PythonTestUtils",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "PyRuntimeInfoTest",
    srcs = ["PyRuntimeInfoTest.java"],
    deps = [
        ":PythonTestUtils",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/rules/python",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "PythonStarlarkApiTest",
    srcs = ["PythonStarlarkApiTest.java"],
    deps = [
        ":PythonTestUtils",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/rules/python",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)
