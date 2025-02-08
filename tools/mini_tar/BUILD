# -*- coding: utf-8 -*-

load("@rules_python//python:defs.bzl", "py_binary", "py_test")
load("//tools/mini_tar:tar.bzl", "mini_tar")

licenses(["notice"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//visibility:public"],
)

exports_files([
    "BUILD",
    "tar.bzl",
])

py_binary(
    name = "mini_tar",
    srcs = ["mini_tar.py"],
    visibility = ["//visibility:public"],
)

# Integration tests may depend on this target to pull mini_tar into a test.
# Then can use
#   http_repository(name='mini_tar', url="file://mini_tar_srcs.tar")
mini_tar(
    name = "mini_tar_srcs",
    srcs = [":srcs"],
    package_dir = "foo",
    visibility = ["//visibility:public"],
)

# tests

py_test(
    name = "mini_tar_test",
    srcs = [
        "mini_tar.py",
        "mini_tar_test.py",
    ],
    data = [":mini_tar_srcs"],
)
