load("@rules_python//python:defs.bzl", "py_binary", "py_test")

package(default_visibility = ["//tools:__pkg__"])

py_binary(
    name = "instrumentation_test_check",
    srcs = ["instrumentation_test_check.py"],
    python_version = "PY3",
    srcs_version = "PY3",
    deps = ["//third_party/py/abseil"],
)

py_test(
    name = "instrumentation_test_check_test",
    srcs = ["instrumentation_test_check_test.py"],
    deps = [":instrumentation_test_check"],
)

filegroup(
    name = "srcs",
    srcs = glob(
        ["**"],
        exclude = [
            "*~",
            ".*",
        ],
    ),
)

filegroup(
    name = "embedded_tools",
    srcs = [
        "BUILD.tools",
        "android_extensions.bzl",
    ],
)

platform(
    name = "arm64-v8a",
    constraint_values = [
        "@platforms//os:android",
        "@platforms//cpu:arm64",
    ],
    visibility = ["//visibility:public"],
)
