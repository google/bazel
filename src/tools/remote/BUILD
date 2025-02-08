load("@rules_java//java:defs.bzl", "java_binary")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//:__pkg__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/tools/remote/src/main/cpp/testonly_output_service:srcs",
        "//src/tools/remote/src/main/java/com/google/devtools/build/remote/worker:srcs",
        "//src/tools/remote/src/test/java/com/google/devtools/build/remote/worker:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

java_binary(
    name = "worker",
    jvm_flags = [
        # Disables functionality in StringUtil that relies on Bazel's own internal string representation.
        "-Dbazel.internal.UnicodeStrings=true",
    ],
    main_class = "com.google.devtools.build.remote.worker.RemoteWorker",
    visibility = ["//visibility:public"],
    runtime_deps = ["//src/tools/remote/src/main/java/com/google/devtools/build/remote/worker"],
)
