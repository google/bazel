load("@rules_java//java:defs.bzl", "java_library", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_testonly = 1,
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["*"]),
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "ShTests_lib",
    srcs = glob(
        ["*.java"],
    ),
    data = ["@rules_shell//shell:rules_bzl"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/packages:testutil",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@rules_java//java/runfiles",
    ],
)

java_test(
    name = "ShTests",
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = [
        ":ShTests_lib",
        "//src/test/java/com/google/devtools/build/lib:test_runner",
    ],
)
