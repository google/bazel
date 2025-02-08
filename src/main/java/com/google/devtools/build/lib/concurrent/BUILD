load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

# Library of concurrency utilities.
java_library(
    name = "concurrent",
    srcs = glob(
        ["*.java"],
        exclude = ["SettableFutureKeyedValue.java"],
    ),
    deps = [
        "//src/main/java/com/google/devtools/build/lib/unsafe:unsafe-provider",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "thread_safety",
    srcs = ["ThreadSafety.java"],
)

java_library(
    name = "settable_future_keyed_value",
    srcs = ["SettableFutureKeyedValue.java"],
    deps = ["//third_party:guava"],
)
