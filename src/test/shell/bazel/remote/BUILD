package(default_visibility = ["//visibility:private"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src/test/shell/bazel:__pkg__"],
)

sh_library(
    name = "remote_utils",
    testonly = True,
    srcs = ["remote_utils.sh"],
    data = [
        "//src/tools/remote:worker",
        "//src/tools/remote:worker_deploy.jar",
    ],
    visibility = ["//src/test/shell/bazel:__pkg__"],
)

sh_test(
    name = "remote_execution_test",
    size = "large",
    timeout = "eternal",
    srcs = ["remote_execution_test.sh"],
    data = [
        ":remote_utils",
        ":uds_proxy.py",
        "//src/test/shell/bazel:test-deps",
        "//src/tools/remote:worker",
        "@bazel_tools//tools/bash/runfiles",
        "@local_jdk//:jdk",  # for remote_helpers setup_localjdk_javabase
    ],
    shard_count = 5,
    tags = [
        "requires-network",  # for Bzlmod (apple_support)
    ],
)

sh_test(
    name = "build_without_the_bytes_test",
    size = "large",
    timeout = "eternal",
    srcs = ["build_without_the_bytes_test.sh"],
    data = [
        ":remote_utils",
        "//src/test/shell/bazel:test-deps",
        "//src/tools/remote:worker",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 5,
)

sh_test(
    name = "remote_execution_http_test",
    size = "large",
    srcs = ["remote_execution_http_test.sh"],
    data = [
        ":remote_utils",
        "//src/test/shell/bazel:test-deps",
        "//src/tools/remote:worker",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "requires-network",
    ],
)

sh_test(
    name = "remote_execution_sandboxing_test",
    size = "large",
    srcs = ["remote_execution_sandboxing_test.sh"],
    data = [
        ":remote_utils",
        "//src/test/shell:sandboxing_test_utils.sh",
        "//src/test/shell/bazel:test-deps",
        "//src/tools/remote:worker",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "remote_execution_tls_test",
    size = "large",
    timeout = "eternal",
    srcs = ["remote_execution_tls_test.sh"],
    args = ["--tls"],
    data = [
        ":remote_utils",
        "//src/test/shell/bazel:test-deps",
        "//src/test/testdata/test_tls_certificate",
        "//src/tools/remote:worker",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "remote_execution_mtls_test",
    size = "large",
    timeout = "eternal",
    srcs = ["remote_execution_tls_test.sh"],
    args = ["--mtls"],
    data = [
        ":remote_utils",
        "//src/test/shell/bazel:test-deps",
        "//src/test/testdata/test_tls_certificate",
        "//src/tools/remote:worker",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "remote_execution_with_xattr_test",
    size = "large",
    timeout = "eternal",
    srcs = ["remote_execution_with_xattr_test.sh"],
    data = [
        ":remote_utils",
        "//src/test/shell/bazel:test-deps",
        "//src/tools/remote:worker",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "remote_build_event_uploader_test",
    size = "large",
    srcs = ["remote_build_event_uploader_test.sh"],
    data = [
        ":remote_utils",
        "//src/test/shell/bazel:test-deps",
        "//src/tools/remote:worker",
        "@bazel_tools//tools/bash/runfiles",
    ],
)
