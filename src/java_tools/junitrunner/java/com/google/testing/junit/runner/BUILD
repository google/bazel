load("@rules_java//java:defs.bzl", "java_binary", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = [
        "//src/java_tools/junitrunner:junitrunner_package_group",
    ],
)

java_library(
    name = "test_runner",
    srcs = [
        "BazelTestRunner.java",
    ],
    deps = [
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/internal",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/junit4",
        "//third_party:junit4",
    ],
)

java_binary(
    name = "Runner",
    main_class = "com.google.testing.junit.runner.BazelTestRunner",
    visibility = [
        "//src:__subpackages__",
        "//src/java_tools/junitrunner:junitrunner_package_group",
    ],
    runtime_deps = [
        ":test_runner",
    ],
)

filegroup(
    name = "srcs",
    srcs = glob(["**/*.java"]) + [
        "BUILD",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/internal:srcs",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/junit4:srcs",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/model:srcs",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/sharding:srcs",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/util:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)
