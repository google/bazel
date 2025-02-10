exports_files([
    "sh_toolchain.bzl",
])

alias(
    name = "toolchain_type",
    actual = "@rules_shell//shell:toolchain_type",
    visibility = ["//visibility:public"],
)

filegroup(
    name = "bzl_srcs",
    srcs = glob(["*.bzl"]),
    visibility = ["//tools:__pkg__"],
)
