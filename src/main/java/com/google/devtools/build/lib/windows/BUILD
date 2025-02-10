load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "file",
    srcs = ["WindowsFileOperations.java"],
    deps = ["//src/main/java/com/google/devtools/build/lib/jni"],
)

java_library(
    name = "processes",
    srcs = ["WindowsProcesses.java"],
    deps = ["//src/main/java/com/google/devtools/build/lib/jni"],
)

java_library(
    name = "windows_short_path",
    srcs = ["WindowsShortPath.java"],
)

java_library(
    name = "windows",
    srcs = [
        "WindowsFileSystem.java",
    ],
    deps = [
        ":file",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/util:string_encoding",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)
