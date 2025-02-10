load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = [
        "//src/java_tools/junitrunner:junitrunner_package_group",
    ],
)

# JUnit4 test runner core code.
java_library(
    name = "junit4",
    srcs = glob(["*.java"]),
    deps = [
        "//src/java_tools/junitrunner/java/com/google/testing/junit/junit4:runner",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/internal",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/internal:junit4",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/internal:system_exit_detecting_shutdown_hook",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/model",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/sharding",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/util",
        "//third_party:error_prone_annotations",
        "//third_party:jsr305",
        "//third_party:jsr330_inject",
        "//third_party:junit4",
    ],
)

filegroup(
    name = "srcs",
    srcs = glob(["**/*.java"]) + ["BUILD"],
    visibility = ["//src:__subpackages__"],
)
