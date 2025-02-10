load("@bazel_pip_dev_deps//:requirements.bzl", "requirement")
load("@rules_python//python:defs.bzl", "py_library", "py_test")
load("//:workspace_deps.bzl", "gen_workspace_stanza")

package(default_visibility = ["//visibility:private"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src:__pkg__"],
)

filegroup(
    name = "test-deps",
    testonly = 1,
    srcs = ["//src:bazel"],
    data = [
        "//src/tools/remote:worker",
        "//src/tools/remote:worker_deploy.jar",
    ],
)

py_library(
    name = "test_base",
    testonly = 1,
    srcs = ["test_base.py"],
    data = [
        ":default_repos_stanza.txt",
        ":test-deps",
        "//src/test/tools/bzlmod:MODULE.bazel.lock",
    ],
    visibility = [
        "//src/test/py/bazel:__pkg__",
        "//third_party/def_parser:__pkg__",
        "//tools/android:__pkg__",
        "//tools/build_rules:__pkg__",
        "//tools/ctexplain:__pkg__",
        "//tools/python:__pkg__",
    ],
    deps = [
        "//third_party/py/abseil",
        requirement("bazel-runfiles"),
    ],
)

gen_workspace_stanza(
    name = "default_repos_stanza",
    out = "default_repos_stanza.txt",
    postamble = "load('@rules_python//python:repositories.bzl', 'py_repositories')\n" +
                "py_repositories()",
    preamble = "load('@bazel_tools//tools/build_defs/repo:http.bzl', 'http_archive')",
    repos = [
        "bazel_skylib",
        "rules_license",
        "rules_cc",
        "rules_python",
        "rules_shell",
        "com_google_protobuf",
    ],
)

py_test(
    name = "action_temp_test",
    size = "medium",
    srcs = ["action_temp_test.py"],
    deps = [":test_base"],
)

py_test(
    name = "cc_import_test",
    size = "large",
    srcs = ["cc_import_test.py"],
    deps = [":test_base"],
)

py_test(
    name = "py_test",
    size = "large",
    srcs = ["py_test.py"],
    deps = [":test_base"],
)

py_test(
    name = "bazel_server_mode_test",
    size = "medium",
    srcs = ["bazel_server_mode_test.py"],
    deps = [":test_base"],
)

py_test(
    name = "bazel_clean_test",
    size = "medium",
    srcs = ["bazel_clean_test.py"],
    deps = [":test_base"],
)

py_test(
    name = "bazel_external_repository_test",
    size = "large",
    srcs = ["bazel_external_repository_test.py"],
    data = glob(["testdata/bazel_external_repository_test/**"]),
    tags = [
        "requires-network",
    ],
    deps = [
        ":test_base",
    ],
)

py_test(
    name = "bazel_windows_test",
    size = "medium",
    srcs = select({
        "//src/conditions:windows": ["bazel_windows_test.py"],
        "//conditions:default": ["empty_test.py"],
    }),
    main = select({
        "//src/conditions:windows": "bazel_windows_test.py",
        "//conditions:default": "empty_test.py",
    }),
    deps = select({
        "//src/conditions:windows": [":test_base"],
        "//conditions:default": [],
    }),
)

py_test(
    name = "bazel_windows_symlinks_test",
    size = "medium",
    srcs = select({
        "//src/conditions:windows": ["bazel_windows_symlinks_test.py"],
        "//conditions:default": ["empty_test.py"],
    }),
    main = select({
        "//src/conditions:windows": "bazel_windows_symlinks_test.py",
        "//conditions:default": "empty_test.py",
    }),
    deps = select({
        "//src/conditions:windows": [":test_base"],
        "//conditions:default": [],
    }),
)

py_test(
    name = "windows_remote_test",
    size = "medium",
    srcs = select({
        "//src/conditions:windows": ["windows_remote_test.py"],
        "//conditions:default": ["empty_test.py"],
    }),
    main = select({
        "//src/conditions:windows": "windows_remote_test.py",
        "//conditions:default": "empty_test.py",
    }),
    # TODO - ilist@: fix the setup of rules_shell for this test
    tags = ["manual"],
    deps = select({
        "//src/conditions:windows": [":test_base"],
        "//conditions:default": [],
    }),
)

py_test(
    name = "launcher_test",
    size = "large",
    srcs = ["launcher_test.py"],
    deps = [":test_base"],
)

py_test(
    name = "runfiles_test",
    timeout = "long",
    srcs = ["runfiles_test.py"],
    data = glob(["testdata/runfiles_test/**"]),
    tags = [
        "requires-network",
    ],
    deps = [
        ":test_base",
    ],
)

py_test(
    name = "runfiles_sandboxed_test",
    timeout = "long",
    srcs = ["runfiles_sandboxed_test.py"],
    data = glob(["testdata/runfiles_test/**"]),
    tags = [
        # Windows does not support sandboxing yet.
        "no_windows",
        "requires-network",
    ],
    deps = [":test_base"],
)

py_test(
    name = "bazel_windows_cpp_test",
    size = "large",
    srcs = select({
        "//src/conditions:windows": ["bazel_windows_cpp_test.py"],
        "//conditions:default": ["empty_test.py"],
    }),
    main = select({
        "//src/conditions:windows": "bazel_windows_cpp_test.py",
        "//conditions:default": "empty_test.py",
    }),
    deps = select({
        "//src/conditions:windows": [":test_base"],
        "//conditions:default": [],
    }),
)

py_test(
    name = "bazel_remote_cache_decompression_test",
    srcs = ["remote/cache_decompression_test.py"],
    main = "remote/cache_decompression_test.py",
    tags = [
        "no-sandbox",  # This test sets up mock cache on localhost.
    ],
    deps = [":test_base"],
)

py_test(
    name = "test_wrapper_test",
    srcs = select({
        "//src/conditions:windows": ["test_wrapper_test.py"],
        "//conditions:default": ["empty_test.py"],
    }),
    data = select({
        "//src/conditions:windows": [
            "native_test.bzl",
            ":printargs",
        ],
        "//conditions:default": [],
    }),
    main = select({
        "//src/conditions:windows": "test_wrapper_test.py",
        "//conditions:default": "empty_test.py",
    }),
    deps = select({
        "//src/conditions:windows": [":test_base"],
        "//conditions:default": [],
    }),
)

cc_binary(
    name = "printargs",
    testonly = 1,
    srcs = ["printargs.cc"],
)

py_test(
    name = "first_time_use_test",
    srcs = ["first_time_use_test.py"],
    deps = [":test_base"],
)

py_test(
    name = "query_test",
    size = "medium",
    srcs = ["query_test.py"],
    deps = [":test_base"],
)

py_test(
    name = "genrule_test",
    size = "medium",
    srcs = ["genrule_test.py"],
    deps = [":test_base"],
)

py_library(
    name = "bzlmod_test_utils",
    srcs = ["bzlmod/test_utils.py"],
)

py_test(
    name = "bazel_module_test",
    size = "large",
    timeout = "eternal",
    srcs = ["bzlmod/bazel_module_test.py"],
    tags = [
        "requires-network",
    ],
    deps = [
        ":bzlmod_test_utils",
        ":test_base",
    ],
)

py_test(
    name = "bazel_lockfile_test",
    size = "large",
    srcs = ["bzlmod/bazel_lockfile_test.py"],
    shard_count = 5,
    tags = [
        "requires-network",
    ],
    deps = [
        ":bzlmod_test_utils",
        ":test_base",
    ],
)

py_test(
    name = "bazel_fetch_test",
    size = "medium",
    srcs = ["bzlmod/bazel_fetch_test.py"],
    data = ["//tools/build_defs/repo:mock_srcs"],
    shard_count = 5,
    tags = [
        "requires-network",
    ],
    deps = [
        ":bzlmod_test_utils",
        ":test_base",
    ],
)

py_test(
    name = "bazel_vendor_test",
    size = "large",
    srcs = ["bzlmod/bazel_vendor_test.py"],
    data = ["//tools/build_defs/repo:mock_srcs"],
    shard_count = 5,
    tags = [
        "requires-network",
    ],
    deps = [
        ":bzlmod_test_utils",
        ":test_base",
    ],
)

py_test(
    name = "bazel_overrides_test",
    size = "large",
    srcs = ["bzlmod/bazel_overrides_test.py"],
    tags = [
        "requires-network",
    ],
    deps = [
        ":bzlmod_test_utils",
        ":test_base",
    ],
)

py_test(
    name = "bazel_repo_mapping_test",
    size = "large",
    srcs = ["bzlmod/bazel_repo_mapping_test.py"],
    tags = [
        "requires-network",
    ],
    deps = [
        ":bzlmod_test_utils",
        ":test_base",
    ],
)

py_test(
    name = "bazel_yanked_versions_test",
    size = "large",
    srcs = ["bzlmod/bazel_yanked_versions_test.py"],
    tags = [
        "requires-network",
    ],
    deps = [
        ":bzlmod_test_utils",
        ":test_base",
    ],
)

py_test(
    name = "options_test",
    srcs = ["options_test.py"],
    deps = [":test_base"],
)

py_test(
    name = "bzlmod_query_test",
    size = "large",
    srcs = ["bzlmod/bzlmod_query_test.py"],
    tags = [
        "requires-network",
    ],
    deps = [
        ":bzlmod_test_utils",
        ":test_base",
    ],
)

py_test(
    name = "mod_command_test",
    size = "large",
    srcs = ["bzlmod/mod_command_test.py"],
    shard_count = 8,
    tags = [
        "requires-network",
    ],
    deps = [
        ":bzlmod_test_utils",
        ":test_base",
    ],
)

py_test(
    name = "bzlmod_credentials_test",
    size = "large",
    srcs = ["bzlmod/bzlmod_credentials_test.py"],
    tags = [
        "no_windows",  # test uses a Python script as a credential helper
        "requires-network",
    ],
    deps = [
        ":bzlmod_test_utils",
        ":test_base",
    ],
)

py_test(
    name = "external_repo_completion_test",
    size = "large",
    srcs = ["bzlmod/external_repo_completion_test.py"],
    data = ["//scripts:bash_completion"],
    tags = [
        "no_windows",  # //scripts:bash_completion does not build on Windows
        "requires-network",
    ],
    deps = [
        ":bzlmod_test_utils",
        ":test_base",
        requirement("bazel-runfiles"),
    ],
)
