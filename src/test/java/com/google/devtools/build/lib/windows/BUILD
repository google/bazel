load("@rules_java//java:defs.bzl", "java_binary", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_testonly = 1,
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["*"]) + [
        "//src/test/java/com/google/devtools/build/lib/windows/util:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

# Tests that need to run on Windows
java_test(
    name = "windows-tests",
    srcs = glob(
        ["*.java"],
        exclude = ["MockSubprocess.java"],
    ),
    data = [
        ":MockSubprocess_deploy.jar",
        ":printarg",
    ],
    jvm_flags = [
        "-Dbazel.windows_unix_root=C:/fake/msys",
    ],
    test_class = "com.google.devtools.build.lib.AllTests",
    runtime_deps = [
        "//src/test/java/com/google/devtools/build/lib:test_runner",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/windows",
        "//src/main/java/com/google/devtools/build/lib/windows:file",
        "//src/main/java/com/google/devtools/build/lib/windows:processes",
        "//src/main/java/com/google/devtools/build/lib/windows:windows_short_path",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//src/test/java/com/google/devtools/build/lib/windows/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@rules_java//java/runfiles",
    ],
)

java_binary(
    name = "MockSubprocess",
    testonly = 1,
    srcs = ["MockSubprocess.java"],
    deps = ["//third_party:guava"],
)

cc_binary(
    name = "printarg",
    testonly = 1,
    srcs = ["printarg.cc"],
)
