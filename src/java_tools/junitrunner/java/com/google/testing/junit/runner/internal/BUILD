load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = [
        "//src/java_tools/junitrunner:junitrunner_package_group",
    ],
)

# Internal code for the JUnit runner that does not depend on JUnit 4.
# Code used by the JUnit runner that does depend on JUnit 4
# lives in the ":junit4" rule.
java_library(
    name = "internal",
    srcs = glob(
        ["*.java"],
        exclude = ["SystemExitDetectingShutdownHook.java"],
    ),
    deps = [
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/util",
        "//third_party:jsr330_inject",
    ],
)

java_library(
    name = "system_exit_detecting_shutdown_hook",
    srcs = ["SystemExitDetectingShutdownHook.java"],
)

# Internal code for the JUnit runner that depends on JUnit 4.
# Code used by the JUnit runner that doesn't depend on JUnit 4
# lives in the "internal" rule.
java_library(
    name = "junit4",
    srcs = glob(["junit4/*.java"]),
    deps = [
        ":internal",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/junit4:runner",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/model",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner/util",
        "//third_party:jsr330_inject",
        "//third_party:junit4",
    ],
)

filegroup(
    name = "srcs",
    srcs = glob(["**/*.java"]) + ["BUILD"],
    visibility = ["//src:__subpackages__"],
)
