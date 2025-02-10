load("@rules_java//java:defs.bzl", "java_binary", "java_library", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = [
        "//src/java_tools/junitrunner:junitrunner_package_group",
    ],
)

# Libraries
# =========================================================
java_library(
    name = "tests",
    testonly = 1,
    srcs = glob(
        ["**/*.java"],
        exclude = ["testbed/*.java"],
    ),
    javacopts = [
    ],
    deps = [
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/internal",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/internal:junit4",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/internal:system_exit_detecting_shutdown_hook",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/junit4",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/model",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/sharding",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/sharding/api",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/sharding/testing",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/util",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestUtils",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:truth",
    ],
)

java_binary(
    name = "TestbedBinary",
    testonly = 1,
    main_class = "com.google.testing.junit.runner.BazelTestRunner",
    runtime_deps = [
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner:test_runner",
        "//src/java_tools/junitrunner/javatests/com/google/testing/junit/runner/testbed",
    ],
)

# Tests
# =========================================================

java_test(
    name = "AllTests",
    args = glob(["**/*Test.java"]),
    main_class = "com.google.testing.junit.runner.TestRunner",
    use_testrunner = 0,
    runtime_deps = [":tests"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/java_tools/junitrunner/javatests/com/google/testing/junit/runner/testbed:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

# BazelTestRunner integration tests
sh_test(
    name = "junit4_testbridge_integration_test",
    size = "medium",
    srcs = ["junit4_testbridge_integration_tests.sh"],
    args = [
        "$(location //src/test/shell:unittest.bash)",
        "$(location :TestbedBinary)",
        "bazel.test_suite",
    ],
    data = [
        ":TestbedBinary",
        "//src/test/shell:bashunit",
        "//src/test/shell:unittest.bash",
    ],
)

sh_test(
    name = "utf8_test_log_test",
    size = "medium",
    srcs = ["utf8_test_log_test.sh"],
    args = [
        "$(location //src/test/shell:unittest.bash)",
        "$(location :TestbedBinary)",
        "bazel.test_suite",
    ],
    data = [
        ":TestbedBinary",
        "//src/test/shell:bashunit",
        "//src/test/shell:unittest.bash",
    ],
)

sh_test(
    name = "antxmlresultwriter_integration_test",
    size = "medium",
    srcs = ["antxmlresultwriter_integration_test.sh"],
    args = [
        "$(location //src/test/shell:unittest.bash)",
        "$(location :TestbedBinary)",
        "$(location //src/java_tools/junitrunner/javatests/com/google/testing/junit/runner/testbed:XmlOutputExercises.ant.xml)",
    ],
    data = [
        ":TestbedBinary",
        "//src/java_tools/junitrunner/javatests/com/google/testing/junit/runner/testbed:XmlOutputExercises.ant.xml",
        "//src/test/shell:bashunit",
        "//src/test/shell:unittest.bash",
    ],
)

sh_test(
    name = "deploy_jar_integration_test",
    size = "medium",
    srcs = ["deploy_jar_integration_tests.sh"],
    args = [
        "$(location //src/test/shell:unittest.bash)",
        "$(JAVABASE)",
        "$(location :TestbedBinary_deploy.jar)",
    ],
    data = [
        ":TestbedBinary_deploy.jar",
        "//src/test/shell:bashunit",
        "//src/test/shell:unittest.bash",
        "@bazel_tools//tools/jdk:current_java_runtime",
    ],
    toolchains = ["@bazel_tools//tools/jdk:current_java_runtime"],
    deps = [
        "@bazel_tools//tools/bash/runfiles",
    ],
)

java_binary(
    name = "ProgramThatCallsSystemExit",
    srcs = ["ProgramThatCallsSystemExit.java"],
    deps = [
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/internal:system_exit_detecting_shutdown_hook",
    ],
)

sh_test(
    name = "system_exit_detecting_test",
    srcs = ["system_exit_detecting_test.sh"],
    args = [
        "$(location //src/test/shell:unittest.bash)",
        "$(location :ProgramThatCallsSystemExit_deploy.jar)",
        "$(JAVABASE)",
        "$(location :testdata/system_exit_detecting_test_stack.txt)",
    ],
    data = [
        ":ProgramThatCallsSystemExit_deploy.jar",
        ":testdata/system_exit_detecting_test_stack.txt",
        "//src/test/shell:bashunit",
        "//src/test/shell:unittest.bash",
        "@bazel_tools//tools/jdk:current_java_runtime",
    ],
    toolchains = ["@bazel_tools//tools/jdk:current_java_runtime"],
    deps = [
        "@bazel_tools//tools/bash/runfiles",
    ],
)

exports_files([
    "stack_trace_integration_tests.sh",
    "junit4_testbridge_integration_tests.sh",
    "utf8_test_log_test.sh",
    "deploy_jar_integration_tests.sh",
    "system_exit_detecting_test.sh",
])
