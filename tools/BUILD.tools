load(":build_defs.bzl", "BZLMOD_ENABLED")

package(default_visibility = ["//visibility:public"])

alias(
    name = "host_platform",
    actual = "@platforms//host" if BZLMOD_ENABLED else "@internal_platforms_do_not_use//host",
)

# Platform used for invalid or empty (e.g. builtin) Bazel actions.
platform(
    name = "internal_platform",
    visibility = ["//visibility:private"],
)

# All bzl files in the built in repo '@bazel_tools'.
filegroup(
    name = "bzl_srcs",
    srcs = [
        "//tools:build_defs.bzl",
        "//tools/android:bzl_srcs",
        "//tools/build_defs:bzl_srcs",
        "//tools/build_rules:bzl_srcs",
        "//tools/cpp:bzl_srcs",
        "//tools/jdk:bzl_srcs",
        "//tools/osx:bzl_srcs",
        "//tools/python:bzl_srcs",
        "//tools/sh:bzl_srcs",
        "//tools/test:bzl_srcs",
    ],
)
