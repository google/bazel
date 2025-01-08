exports_files(["MODULE.bazel.lock"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

# To avoid accessing BCR in most tests, we generate and checkin a default MODULE.bazel.lock
# file in an empty workspace that will be used in integration tests setup.
sh_binary(
    name = "update_default_lock_file",
    srcs = ["update_default_lock_file.sh"],
    data = ["//src:bazel"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "verify_default_lock_file",
    srcs = ["verify_default_lock_file.sh"],
    data = [
        ":MODULE.bazel.lock",
        "//:MODULE.bazel.lock",
        "//src:MODULE.tools",
        "//src:bazel",
        "//src/test/shell/bazel:test-deps",
    ],
    tags = ["manual"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)
