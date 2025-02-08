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
    name = "ConcurrentTests_lib",
    srcs = glob(["*.java"]),
    deps = [
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/concurrent:settable_future_keyed_value",
        "//src/main/java/com/google/devtools/build/lib/unsafe:unsafe-provider",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestThread",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestUtils",
        "//src/test/java/com/google/devtools/build/lib/testutil:ThrowingRecordableRunnableWrapper",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:truth",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)

java_test(
    name = "ConcurrentTests",
    size = "medium",
    tags = [
        "foundations",
    ],
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = [
        ":ConcurrentTests_lib",
        "//src/test/java/com/google/devtools/build/lib:test_runner",
    ],
)
