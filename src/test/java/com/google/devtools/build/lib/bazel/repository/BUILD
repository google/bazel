load("@rules_java//java:defs.bzl", "java_library", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_testonly = 1,
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["*"]) + [
        "//src/test/java/com/google/devtools/build/lib/bazel/repository/cache:srcs",
        "//src/test/java/com/google/devtools/build/lib/bazel/repository/downloader:srcs",
        "//src/test/java/com/google/devtools/build/lib/bazel/repository/starlark:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "RepositoryTests_lib",
    srcs = glob(
        ["*.java"],
        exclude = [
            "LocalConfigPlatformFunctionTest.java",
        ],
    ),
    data = [
        "test_decompress_archive.tar.gz",
        "test_decompress_archive.zip",
        "test_files.ar",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/bazel/repository",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository:repository_options",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_function",
        "//src/main/java/com/google/devtools/build/lib/unix",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs",
        "//src/main/java/com/google/devtools/common/options",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestUtils",
        "//third_party:guava",
        "//third_party:java-diff-utils",
        "//third_party:junit4",
        "//third_party:truth",
        "@rules_java//java/runfiles",
    ],
)

java_test(
    name = "RepositoryTests",
    tags = [
        "rules",
    ],
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = [
        ":RepositoryTests_lib",
        "//src/test/java/com/google/devtools/build/lib:test_runner",
    ],
)

java_library(
    name = "BazelRepositoryTests_lib",
    srcs = [
        "LocalConfigPlatformFunctionTest.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis/platform",
        "//src/main/java/com/google/devtools/build/lib/analysis/platform:utils",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "BazelRepositoryTests",
    tags = [
        "manual",
        "rules",
    ],
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = [
        ":BazelRepositoryTests_lib",
        "//src/test/java/com/google/devtools/build/lib:test_runner",
    ],
)
