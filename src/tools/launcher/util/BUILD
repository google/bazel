load("//src/tools/launcher:win_rules.bzl", "win_cc_library", "win_cc_test")

package(default_visibility = ["//src/tools/launcher:__subpackages__"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//visibility:public"],
)

win_cc_library(
    name = "data_parser",
    srcs = ["data_parser.cc"],
    hdrs = ["data_parser.h"],
    deps = [":util"],
)

win_cc_library(
    name = "util",
    srcs = ["launcher_util.cc"],
    hdrs = ["launcher_util.h"],
    deps = ["//src/main/cpp/util:filesystem"],
)

win_cc_test(
    name = "util_test",
    srcs = ["launcher_util_test.cc"],
    deps = [
        ":util",
        "//src/main/native/windows:lib-file",
        "@com_google_googletest//:gtest_main",
    ],
)

win_cc_test(
    name = "data_parser_test",
    srcs = ["data_parser_test.cc"],
    deps = [
        ":data_parser",
        "@com_google_googletest//:gtest_main",
    ],
)
