package(default_visibility = ["//visibility:public"])

filegroup(
    name = "BUILD-jdk",
    srcs = [":BUILD.tools"],
)

filegroup(
    name = "srcs",
    srcs = [
        "BUILD",
        "BUILD.java_tools",
        "BUILD.java_tools_prebuilt",
        "BUILD-jdk",  # Tools are build from the workspace for tests.
        "default_java_toolchain.bzl",
        "java_toolchain_alias.bzl",
        "launcher_flag_alias.bzl",
        "local_java_repository.bzl",
        "nosystemjdk/README",
        "proguard_allowlister.py",
        "proguard_allowlister_test.py",
        "proguard_allowlister_test_input.pgcfg",
        "remote_java_repository.bzl",
        "toolchain_utils.bzl",
    ],
)

filegroup(
    name = "bzl_srcs",
    srcs = glob(["*.bzl"]),
    visibility = ["//tools:__pkg__"],
)

filegroup(
    name = "package-srcs",
    srcs = glob(["**"]),
)
