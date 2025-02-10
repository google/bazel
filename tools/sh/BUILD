package(default_visibility = ["//visibility:private"])

filegroup(
    name = "srcs",
    srcs = glob(
        ["**"],
        exclude = [
            "*~",
            ".*",
        ],
    ),
    visibility = ["//tools:__pkg__"],
)

filegroup(
    name = "embedded_tools",
    srcs = [
        "BUILD.tools",
        "sh_toolchain.bzl",
    ],
    visibility = ["//tools:__pkg__"],
)
