# Description:
#   C++ utility source for Bazel
package_group(
    name = "ijar",
    packages = [
        "//third_party/ijar/...",
    ],
)

cc_library(
    name = "util",
    hdrs = [
        "errors.h",
        "file.h",
        "file_platform.h",
        "md5.h",
        "numbers.h",
        "path.h",
        "path_platform.h",
        "port.h",
    ],
    visibility = ["//visibility:public"],
    deps = [
        ":blaze_exit_code",
        ":errors",
        ":filesystem",
        ":md5",
        ":numbers",
        ":port",
        ":strings",
    ],
)

cc_library(
    name = "filesystem",
    srcs = [
        "file.cc",
        "path.cc",
    ] + select({
        "//src/conditions:windows": [
            "file_windows.cc",
            "path_windows.cc",
        ],
        "//conditions:default": [
            "file_posix.cc",
            "path_posix.cc",
        ],
    }),
    hdrs = [
        "file.h",
        "file_platform.h",
        "path.h",
        "path_platform.h",
    ],
    visibility = [
        ":ijar",
        "//src/main/tools:__pkg__",
        "//src/test/cpp/util:__pkg__",
        "//src/test/native/windows:__pkg__",
        "//src/tools/launcher:__subpackages__",
        "//src/tools/singlejar:__pkg__",
        "//third_party/def_parser:__pkg__",
        "//tools/test:__pkg__",
    ],
    deps = [
        ":blaze_exit_code",
        ":errors",
        ":logging",
        ":strings",
    ] + select({
        "//src/conditions:windows": ["//src/main/native/windows:lib-file"],
        "//conditions:default": [],
    }),
)

cc_library(
    name = "errors",
    srcs = select({
        "//src/conditions:windows": ["errors_windows.cc"],
        "//conditions:default": ["errors_posix.cc"],
    }),
    hdrs = ["errors.h"],
    visibility = [
        ":ijar",
        "//src/main/cpp:__subpackages__",
    ],
    deps = [
        ":logging",
        ":port",
        ":strings",
    ],
)

cc_library(
    name = "port",
    srcs = ["port.cc"],
    hdrs = ["port.h"],
    visibility = [
        "//src/main/native:__pkg__",
    ],
)

cc_library(
    name = "numbers",
    srcs = ["numbers.cc"],
    hdrs = ["numbers.h"],
    deps = [":strings"],
)

cc_library(
    name = "logging",
    srcs = ["logging.cc"],
    hdrs = ["logging.h"],
    visibility = ["//visibility:public"],
    deps = [
        ":blaze_exit_code",
        ":strings",
    ],
)

cc_library(
    name = "bazel_log_handler",
    srcs = ["bazel_log_handler.cc"],
    hdrs = ["bazel_log_handler.h"],
    visibility = ["//visibility:public"],
    deps = [
        ":blaze_exit_code",
        ":filesystem",
        ":logging",
    ],
)

cc_library(
    name = "md5",
    srcs = ["md5.cc"],
    hdrs = ["md5.h"],
    visibility = [
        "//src/main/native:__pkg__",
        "//src/test/cpp/util:__pkg__",
    ],
)

cc_library(
    name = "strings",
    srcs = ["strings.cc"],
    hdrs = ["strings.h"],
    # Automatically propagate the symbol definition to rules depending on this.
    defines = [
        "BLAZE_OPENSOURCE",
    ],
    visibility = ["//visibility:public"],
    deps = [":blaze_exit_code"],
)

cc_library(
    name = "blaze_exit_code",
    hdrs = ["exit_code.h"],
    visibility = ["//visibility:public"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src/main/cpp:__pkg__"],
)

filegroup(
    name = "embedded_tools",
    srcs = glob(["*.cc"]) + glob(["*.h"]) + ["BUILD"],
    visibility = ["//visibility:public"],
)

filegroup(
    name = "embedded_java_tools",
    srcs = glob(["*.cc"]) + glob(["*.h"]) + ["//src/main/native/windows:embedded_java_tools"],
    visibility = ["//visibility:public"],
)

# A zip containing source files used by ijar.
genrule(
    name = "cpp_util_with_deps_zip",
    srcs = [
        ":cpp_util_zip",
        "//src/main/native/windows:native_windows_zip",
    ],
    outs = ["cpp_util_with_deps.zip"],
    cmd = "$(location //src:merge_zip_files) - $@ $(SRCS)",
    tools = ["//src:merge_zip_files"],
    visibility = [
        "//src/tools/singlejar:__pkg__",
        "//third_party/ijar:__pkg__",
    ],
)

genrule(
    name = "cpp_util_zip",
    srcs = glob(["*.cc"]) + glob(["*.h"]),
    outs = ["cpp_util.zip"],
    cmd = "$(location //src:zip_files) src/main/cpp/util $@ $(SRCS)",
    tools = ["//src:zip_files"],
    visibility = ["//visibility:private"],
)
