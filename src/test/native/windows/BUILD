filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src:__pkg__"],
)

cc_binary(
    name = "printarg",
    testonly = 1,
    srcs = ["printarg.cc"],
)

cc_library(
    name = "test_deps",
    testonly = 1,
    deps = select({
        "//src/conditions:windows": [
            "//src/test/cpp/util:windows_test_util",
            "@com_google_googletest//:gtest_main",
        ],
        "//conditions:default": [],
    }),
)

cc_test(
    name = "file_test",
    size = "small",
    srcs = select({
        "//src/conditions:windows": ["file_test.cc"],
        "//conditions:default": ["dummy_test.cc"],
    }),
    deps = select({
        "//src/conditions:windows": [
            ":test_deps",
            "//src/main/native/windows:lib-file",
        ],
        "//conditions:default": [],
    }),
)

cc_test(
    name = "util_test",
    size = "small",
    srcs = select({
        "//src/conditions:windows": ["util_test.cc"],
        "//conditions:default": ["dummy_test.cc"],
    }),
    deps = select({
        "//src/conditions:windows": [
            ":test_deps",
            "//src/main/native/windows:lib-file",
        ],
        "//conditions:default": [],
    }),
)

cc_test(
    name = "process_test",
    size = "small",
    srcs = select({
        "//src/conditions:windows": ["process_test.cc"],
        "//conditions:default": ["dummy_test.cc"],
    }),
    data = [":printarg"],
    deps = select({
        "//src/conditions:windows": [
            ":test_deps",
            "//src/main/cpp/util:filesystem",
            "//src/main/native/windows:lib-file",
            "//src/main/native/windows:lib-process",
            "@rules_cc//cc/runfiles",
        ],
        "//conditions:default": [],
    }),
)

test_suite(name = "all_tests")
