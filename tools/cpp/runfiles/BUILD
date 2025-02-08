package(default_visibility = ["//visibility:private"])

filegroup(
    name = "srcs",
    srcs = glob(
        ["**"],
        exclude = [
            ".*",
            "*~",
        ],
    ),
    visibility = ["//tools/cpp:__pkg__"],
)

filegroup(
    name = "embedded_tools",
    srcs = [
        "BUILD.tools",
        "runfiles.h",
    ],
    visibility = ["//tools/cpp:__pkg__"],
)
