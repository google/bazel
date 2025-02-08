load("@bazel_skylib//rules:write_file.bzl", "write_file")
load("//:workspace_deps.bzl", "gen_workspace_stanza")
load("//src/tools/bzlmod:utils.bzl", "get_canonical_repo_name")

package(default_visibility = ["//visibility:private"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/test/shell/bazel/apple:srcs",
        "//src/test/shell/bazel/remote:srcs",
        "//src/test/shell/bazel/testdata:srcs",
        "//tools/aquery_differ:srcs",
    ],
    visibility = ["//src/test/shell:__pkg__"],
)

gen_workspace_stanza(
    name = "rules_license_stanza",
    out = "rules_license_stanza.txt",
    repos = [
        "rules_license",
    ],
)

filegroup(
    name = "test-deps",
    testonly = 1,
    srcs = [
        ":rules_license_stanza.txt",
        ":test-deps-wo-bazel",
        "//src:bazel",
        "//src/test/shell:bin/bazel",
    ],
    visibility = [
        "//src/main/starlark/tests/builtins_bzl:__subpackages__",
        "//src/test/shell:__subpackages__",
        "//src/test/tools/bzlmod:__subpackages__",
    ],
)

filegroup(
    name = "test-deps-wo-bazel",
    testonly = 1,
    srcs = [
        "cc_api_rules.bzl",
        "coverage_helpers.sh",
        "remote_helpers.sh",
        "testing_server.py",
        ":hamcrest_jar",
        ":junit_jar",
        "//examples:srcs",
        "//src/conditions:srcs",
        "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/genclass:GenClass_deploy.jar",
        "//src/java_tools/junitrunner/java/com/google/testing/junit/runner:Runner_deploy.jar",
        "//src/main/tools:linux-sandbox",
        "//src/main/tools:process-wrapper",
        "//src/test/shell:bashunit",
        "//src/test/shell:integration_test_setup.sh",
        "//src/test/shell:testenv.sh",
        "//src/tools/singlejar",
        "//third_party:srcs",
        "//third_party/ijar",
        "//tools:srcs",
        "@bazel_skylib//:test_deps",
        "@rules_java//toolchains:current_java_runtime",
    ],
    visibility = [
        "//src/test/shell:__subpackages__",
    ],
)

genrule(
    name = "junit_jar",
    srcs = ["@maven//:junit_junit_file"],
    outs = ["junit.jar"],
    cmd = "cp $< $@",
)

genrule(
    name = "hamcrest_jar",
    srcs = ["@maven//:org_hamcrest_hamcrest_core_file"],
    outs = ["hamcrest.jar"],
    cmd = "cp $< $@",
)

sh_test(
    name = "bazel_symlink_test",
    size = "medium",
    srcs = ["bazel_symlink_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_example_test",
    size = "large",
    srcs = ["bazel_example_test.sh"],
    args = ["$(JAVABASE)"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 3,
    tags = [
        "no_windows",
    ],
    toolchains = ["@rules_java//toolchains:current_java_runtime"],
)

sh_test(
    name = "bazel_windows_example_test",
    size = "large",
    srcs = ["bazel_windows_example_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "cpp_darwin_integration_test",
    size = "large",
    srcs = ["cpp_darwin_integration_test.sh"],
    data = [
        ":test-deps",
    ],
    tags = [
        "no_windows",  # darwin-specific test
        "requires-network",  # For Bzlmod
    ],
)

sh_test(
    name = "bazel_tools_test",
    size = "large",
    srcs = ["bazel_tools_test.sh"],
    data = [
        ":test-deps",
    ],
    tags = [
        "no_windows",  # objc-specific test
    ],
)

sh_test(
    name = "bazel_embedded_starlark_test",
    size = "medium",
    srcs = ["bazel_embedded_starlark_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",  # TODO(laszlocsomor): make this run on Windows
    ],
)

sh_test(
    name = "bazel_random_characters_test",
    size = "large",
    srcs = ["bazel_random_characters_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_wrapper_test",
    srcs = ["bazel_wrapper_test.sh"],
    data = [
        ":test-deps",
        "//scripts/packages:wrapper",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "no_windows",  # wrapper is not used on Windows
    ],
)

sh_test(
    name = "bazel_java_test_no_windows",
    size = "large",
    timeout = "eternal",
    srcs = ["bazel_java_test_no_windows.sh"],
    data = [
        ":test-deps",
    ],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_java_test_defaults",
    srcs = ["bazel_java_test_defaults.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        # bzlmod usage requires network
        "requires-network",
    ],
)

sh_test(
    name = "bazel_java_test_defaults_prebuilt",
    srcs = ["bazel_java_test_defaults_prebuilt.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    target_compatible_with = select({
        "@platforms//os:linux": ["@platforms//cpu:x86_64"],
        "@platforms//os:macos": [],
        "@platforms//os:windows": [],
        "//conditions:default": ["@platforms//:incompatible"],
    }),
)

write_file(
    name = "gen_rules_java_repo_name",
    out = "RULES_JAVA_REPO_NAME",
    content = [get_canonical_repo_name("@rules_java")],
)

sh_test(
    name = "bazel_java17_test",
    srcs = ["bazel_java17_test.sh"],
    args = [
        # java_tools zip to test
        "$(rlocationpath //src:java_tools_java8.zip)",
        "$(rlocationpath //src:java_tools_prebuilt_java8.zip)",
    ],
    data = [
        ":gen_rules_java_repo_name",
        ":test-deps",
        # Use java_tools compiled at Java language 8 instead (aligned with the
        # default compilation level of java_tools). For the purpose
        # of this test, we need java_tools to be running with runtime >11 to
        # test the failure modes of incompatible system classpaths.
        "//src:java_tools_prebuilt_java8.zip",
        "//src:java_tools_java8.zip",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["local"],
)

sh_test(
    name = "bazel_rules_java_override_test",
    srcs = ["bazel_rules_java_override_test.sh"],
    data = [
        ":gen_rules_java_repo_name",
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_java_test",
    # TODO(iirina): Investigate if the 'large' and 'eternal' values still apply.
    size = "large",
    timeout = "eternal",
    srcs = ["bazel_java_test.sh"],
    args = [
        "released",
        "released",
    ],
    data = [
        ":gen_rules_java_repo_name",
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    exec_compatible_with = ["//:highcpu_machine"],
    shard_count = 2,
    tags = [
        "requires-network",  # For Bzlmod
    ],
)

JAVA_VERSIONS = ("11", "17", "21")

JAVA_VERSIONS_COVERAGE = ("11", "17", "21")

[
    sh_test(
        name = "bazel_java_test_jdk" + java_version + "_toolchain_head",
        size = "large",
        timeout = "eternal",
        srcs = ["bazel_java_test.sh"],
        args = [
            # java_tools zips to test
            "$(rlocationpath %s)" % ("//src:java_tools_zip" if java_version == "21" else "//src:java_tools_java8.zip"),
            "$(rlocationpath %s)" % ("//src:java_tools_prebuilt_zip" if java_version == "21" else "//src:java_tools_prebuilt_java8.zip"),
            # --java_language_version value
            java_version,
            # --java_runtime_version value
            java_version,
        ],
        data = [
            ":gen_rules_java_repo_name",
            ":test-deps",
            "//src:java_tools_zip" if java_version == "21" else "//src:java_tools_java8.zip",
            "//src:java_tools_prebuilt_zip" if java_version == "21" else "//src:java_tools_prebuilt_java8.zip",
            "@bazel_tools//tools/bash/runfiles",
        ],
        exec_compatible_with = ["//:highcpu_machine"],
        shard_count = 2,
        tags = [
            "requires-network",  # For Bzlmod
        ],
    )
    for java_version in JAVA_VERSIONS
]

[
    sh_test(
        name = "bazel_java_test_local_java_tools_jdk" + java_version,
        size = "large",
        timeout = "eternal",
        srcs = ["bazel_java_test.sh"],
        args = [
            # java_tools zips to test
            "$(LOCAL_JAVA_TOOLS_ZIP_PATH)",
            "$(LOCAL_JAVA_TOOLS_PREBUILT_ZIP_PATH)",
            # --java_language_version value
            java_version,
            # --java_runtime_version value
            java_version,
        ],
        data = [
            ":gen_rules_java_repo_name",
            ":test-deps",
            "@bazel_tools//tools/bash/runfiles",
        ],
        tags = [
            # This test is only run by the java_tools binaries pipeline.
            "manual",
            "requires-network",  # For Bzlmod
        ],
    )
    for java_version in JAVA_VERSIONS
]

sh_test(
    name = "bazel_java_tools_test",
    size = "large",
    srcs = ["bazel_java_tools_test.sh"],
    data = [
        ":test-deps",
        "//src:java_tools.zip",
        "//src:java_tools_prebuilt.zip",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "requires-network",  # For Bzlmod
    ],
)

sh_test(
    name = "bazel_java_tools_dist_test",
    srcs = ["bazel_java_tools_dist_test.sh"],
    data = [
        ":test-deps",
        "//src:java_tools_dist.zip",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

# TODO(b/324251617): downgrade test size to "large" once we no longer use Intel VMs
sh_test(
    name = "bazel_proto_library_test",
    size = "enormous",  # Downloads and compiles protobuf for *every* *test* *case*
    srcs = ["bazel_proto_library_test.sh"],
    data = [":test-deps"],
    exec_compatible_with = ["//:highcpu_machine"],
    shard_count = 2,
    tags = [
        "no_windows",  # Doesn't work on Windows for unknown reason
        "requires-network",  # Allow this test to access internet to fetch rules_proto dependencies.
    ],
)

sh_test(
    name = "bazel_build_event_stream_test",
    size = "medium",
    srcs = ["bazel_build_event_stream_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_ui_test",
    size = "medium",
    srcs = ["bazel_ui_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_rules_test",
    size = "large",
    srcs = ["bazel_rules_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 3,
    tags = ["requires-network"],
)

sh_test(
    name = "bazel_rules_java_test",
    srcs = ["bazel_rules_java_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_rules_cc_test",
    timeout = "eternal",
    srcs = ["bazel_rules_cc_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["requires-network"],
)

sh_test(
    name = "bazel_test_test",
    timeout = "long",
    srcs = ["bazel_test_test.sh"],
    data = [":test-deps"],
    shard_count = 3,
    tags = [
        "no-sandbox",
        "no_windows",
        "requires-network",  # For Bzlmod
    ],
)

sh_test(
    name = "bazel_spawnstats_test",
    srcs = ["bazel_spawnstats_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_coverage_cc_released_test_gcc",
    srcs = ["bazel_coverage_cc_test_gcc.sh"],
    args = ["released"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_coverage_cc_head_test_gcc",
    srcs = ["bazel_coverage_cc_test_gcc.sh"],
    args = [
        "tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator/coverage",
    ],
    data = [
        ":test-deps",
        "//tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:coverage_output_generator_repo",
    ],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_coverage_cc_head_test_llvm",
    srcs = ["bazel_coverage_cc_test_llvm.sh"],
    args = [
        "tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator/coverage",
    ],
    data = [
        ":test-deps",
        "//tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:coverage_output_generator_repo",
    ],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_coverage_cc_released_test_llvm",
    srcs = ["bazel_coverage_cc_test_llvm.sh"],
    args = ["released"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_coverage_java_test",
    size = "large",
    srcs = ["bazel_coverage_java_test.sh"],
    args = [
        "released",
        "released",
        "released",
    ],
    data = [
        ":gen_rules_java_repo_name",
        ":test-deps",
    ],
    tags = [
        "no_windows",
    ],
)

# Test java coverage with the java_toolchain in the released java_tools versions.
[
    sh_test(
        name = "bazel_coverage_java_jdk" + java_version + "_toolchain_released_test",
        size = "large",
        srcs = ["bazel_coverage_java_test.sh"],
        args = [
            # java_tools zip to test
            "released",
            "released",
            # coverage_report_generator to test
            "released",
            # --java_runtime_version value
            java_version,
        ],
        data = [
            ":gen_rules_java_repo_name",
            ":test-deps",
        ],
        tags = [
            "no_windows",
        ],
    )
    for java_version in JAVA_VERSIONS_COVERAGE
]

# Test java coverage with the java_toolchain in the java_tools zip built at head.
[
    sh_test(
        name = "bazel_coverage_java_jdk" + java_version + "_toolchain_head_test",
        size = "large",
        srcs = ["bazel_coverage_java_test.sh"],
        args = [
            # java_tools zips to test
            "$(rlocationpath %s)" % ("//src:java_tools_zip" if java_version == "21" else "//src:java_tools_java8.zip"),
            "$(rlocationpath %s)" % ("//src:java_tools_prebuilt_zip" if java_version == "21" else "//src:java_tools_prebuilt_java8.zip"),
            # WORKSPACE file of the coverage output generator repo to test
            "$(rlocationpath //tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:coverage_empty_workspace)",
            # --java_runtime_version value
            java_version,
        ],
        data = [
            ":gen_rules_java_repo_name",
            ":test-deps",
            "//src:java_tools_prebuilt_zip" if java_version == "21" else "//src:java_tools_prebuilt_java8.zip",
            "//src:java_tools_zip" if java_version == "21" else "//src:java_tools_java8.zip",
            "//tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:coverage_empty_workspace",
            "//tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:coverage_output_generator_repo%s" % ("" if java_version == "21" else "_java8"),
        ],
        tags = ["no_windows"],
    )
    for java_version in JAVA_VERSIONS_COVERAGE
]

sh_test(
    name = "bazel_coverage_py_test",
    srcs = ["bazel_coverage_py_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_coverage_hermetic_py_test",
    srcs = ["bazel_coverage_hermetic_py_test.sh"],
    data = [":test-deps"],
    tags = [
        "manual",  # Bug: https://github.com/bazelbuild/rules_python/issues/2248
        "no_windows",
    ],
)

sh_test(
    name = "bazel_coverage_sh_test",
    srcs = ["bazel_coverage_sh_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_coverage_starlark_test",
    srcs = ["bazel_coverage_starlark_test.sh"],
    args = ["released"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_coverage_starlark_head_test",
    srcs = ["bazel_coverage_starlark_test.sh"],
    args = [
        # WORKSPACE file of the coverage output generator repo to test
        "$(rlocationpath //tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:coverage_empty_workspace)",
    ],
    data = [
        ":test-deps",
        "//tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:coverage_empty_workspace",
        "//tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:coverage_output_generator_repo_java8",
    ],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_coverage_compatibility_test",
    srcs = ["bazel_coverage_compatibility_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_cc_code_coverage_test",
    srcs = ["bazel_cc_code_coverage_test.sh"],
    data = [":test-deps"],
    tags = [
        # C++ coverage is not supported on macOS yet.
        "no_macos",
        "no_windows",
    ],
)

sh_test(
    name = "bazel_thinlto_test",
    srcs = ["bazel_thinlto_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_thinlto_obj_dir_test",
    srcs = ["bazel_thinlto_obj_dir_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_1804",
        "no_windows",
    ],
)

sh_test(
    name = "bazel_layering_check_test",
    srcs = ["bazel_layering_check_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_localtest_test",
    srcs = ["bazel_localtest_test.sh"],
    data = [":test-deps"],
    tags = [
        "no-sandbox",
        "no_windows",
    ],
)

sh_test(
    name = "bazel_package_loader_test",
    srcs = ["bazel_package_loader_test.sh"],
    data = [
        ":test-deps",
        "//src/main/java/com/google/devtools/build/lib/skyframe/packages/testing:BazelPackageLoaderTester",
    ],
    tags = [
        "no_windows",
        "requires-network",
    ],
)

sh_test(
    name = "bazel_permissions_test",
    size = "small",
    srcs = ["bazel_permissions_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "bazel_execute_testlog",
    srcs = ["bazel_execute_testlog.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "allowlist_test",
    size = "medium",
    srcs = ["allowlist_test.sh"],
    data = [":test-deps"],
    shard_count = 3,
    tags = ["no_windows"],
)

genquery(
    name = "embedded_tools_deps",
    expression = "kind(\"cc_(binary|library)\", deps(//src:embedded_tools_jdk_allmodules_srcs))",
    opts = ["--notool_deps"],
    scope = ["//src:embedded_tools_jdk_allmodules_srcs"],
)

sh_test(
    name = "embedded_tools_deps_test",
    size = "medium",
    srcs = ["embedded_tools_deps_test.sh"],
    data = [
        ":embedded_tools_deps",
        ":test-deps",
        "//src/test/shell/bazel/testdata:embedded_tools_deps_test_data",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_docgen_test",
    size = "large",
    srcs = ["bazel_docgen_test.sh"],
    data = ["//src/main/java/com/google/devtools/build/lib:generated_docs"],
    tags = ["no_windows"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "external_starlark_execute_test",
    size = "large",
    srcs = ["external_starlark_execute_test.sh"],
    data = [":test-deps"],
    shard_count = 2,
    tags = ["no_windows"],
)

sh_test(
    name = "external_correctness_test",
    size = "large",
    srcs = ["external_correctness_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "external_integration_test",
    size = "large",
    srcs = ["external_integration_test.sh"],
    data = [
        ":test-deps",
        "//src/test/shell/bazel/testdata:zstd_test_archive.tar.zst",
    ],
    shard_count = 15,
    tags = [
        "no_windows",
        "requires-network",
    ],
)

sh_test(
    name = "external_patching_test",
    size = "medium",
    srcs = ["external_patching_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 3,
)

sh_test(
    name = "external_remote_file_test",
    size = "medium",
    srcs = ["external_remote_file_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 3,
)

sh_test(
    name = "external_path_test",
    size = "medium",
    srcs = ["external_path_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 6,
    tags = ["no_windows"],
)

sh_test(
    name = "starlark_git_repository_test",
    size = "large",
    srcs = ["starlark_git_repository_test.sh"],
    data = [
        ":test-deps",
        "//src/test/shell/bazel/testdata:git-repos",
        "@bazel_tools//tools/bash/runfiles",
    ],
    exec_compatible_with = ["//:highcpu_machine"],
)

sh_test(
    name = "local_repository_test",
    size = "large",
    srcs = ["local_repository_test.sh"],
    data = [":test-deps"],
    shard_count = 3,
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "external_starlark_load_test",
    size = "large",
    srcs = ["external_starlark_load_test.sh"],
    data = [":test-deps"],
    shard_count = 6,
    tags = ["no_windows"],
)

sh_test(
    name = "repository_abort_test",
    size = "large",
    srcs = ["repository_abort_test.sh"],
    data = [
        ":test-deps",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "starlark_repository_test",
    size = "large",
    srcs = ["starlark_repository_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
        "@local_jdk//:jdk",  # for remote_helpers setup_localjdk_javabase
    ],
    shard_count = 10,
    tags = [
        "requires-network",
    ],
)

sh_test(
    name = "starlark_rule_test",
    size = "large",
    srcs = ["starlark_rule_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "tags_propagation_starlark_test",
    size = "large",
    srcs = ["tags_propagation_starlark_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "tags_propagation_native_test",
    size = "large",
    srcs = ["tags_propagation_native_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "disk_cache_test",
    size = "medium",
    srcs = ["disk_cache_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "runfiles_test",
    size = "medium",
    srcs = ["runfiles_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",
        "requires-network",
    ],
)

sh_test(
    name = "empty_package_test",
    srcs = ["empty_package.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "location_test",
    srcs = ["location_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

# To run this test, ensure that maven_dependency_plugin() and
# android_repositories() in WORKSPACE are uncommented.
sh_test(
    name = "maven_starlark_test",
    size = "medium",
    srcs = ["maven_starlark_test.sh"],
    data = [
        ":test-deps",
        "//external:android_sdk_for_testing",
        "@m2//:files",
    ],
    tags = [
        "manual",
        "no_windows",
    ],
)

sh_test(
    name = "python_version_test",
    size = "medium",
    srcs = ["python_version_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["requires-network"],
)

sh_test(
    name = "bazelignore_test",
    size = "medium",
    srcs = ["bazelignore_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "cc_integration_test",
    size = "large",
    srcs = ["cc_integration_test.sh"],
    data = [":test-deps"],
    shard_count = 10,
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "cc_import_starlark_test",
    size = "medium",
    srcs = ["cc_import_starlark_test.sh"],
    data = [
        ":test-deps",
        "//tools/build_defs/cc:cc_import.bzl",
        "//tools/build_defs/cc/tests:cc_import_tests_files",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_docker_sandboxing_test",
    srcs = ["bazel_docker_sandboxing_test.sh"],
    data = [
        ":test-deps",
    ],
    tags = [
        "local",
        "manual",  # TODO(philwo) re-enable once Bazel CI supports Docker again
        "no_windows",
    ],
)

sh_test(
    name = "bazel_sandboxing_test",
    size = "large",
    srcs = ["bazel_sandboxing_test.sh"],
    data = [
        ":test-deps",
        "//src/test/shell:sandboxing_test_utils.sh",
    ],
    tags = [
        "no-sandbox",
        "no_windows",
    ],
)

sh_test(
    name = "bazel_sandboxing_networking_test",
    size = "large",
    srcs = ["bazel_sandboxing_networking_test.sh"],
    data = [
        ":test-deps",
        "//src/test/shell:sandboxing_test_utils.sh",
    ],
    tags = [
        "no-sandbox",
        "no_windows",
        "requires-network",
    ],
)

sh_test(
    name = "bazel_hermetic_sandboxing_test",
    size = "medium",
    srcs = ["bazel_hermetic_sandboxing_test.sh"],
    data = [
        ":test-deps",
        "//src/test/shell:sandboxing_test_utils.sh",
    ],
    tags = [
        "no-sandbox",
        "no_windows",
    ],
)

sh_test(
    name = "bazel_sandboxing_cpp_test",
    srcs = ["bazel_sandboxing_cpp_test.sh"],
    data = [
        ":test-deps",
        "//src/test/shell:sandboxing_test_utils.sh",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "no-sandbox",
        "no_windows",
    ],
)

sh_test(
    name = "bazel_workspace_status_test",
    size = "large",
    srcs = ["bazel_workspace_status_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "client_test",
    size = "medium",
    srcs = ["client_test.sh"],
    data = [
        ":test-deps",
        "//src/test/java/com/google/devtools/build/lib/testutil:external_file_system_lock_helper",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "command_profiler_test",
    size = "medium",
    srcs = ["command_profiler_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "execroot_test",
    size = "medium",
    srcs = ["execroot_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",
        "requires-network",
    ],
)

sh_test(
    name = "bazel_repository_cache_test",
    size = "large",
    srcs = ["bazel_repository_cache_test.sh"],
    data = [":test-deps"],
    shard_count = 6,
    tags = [
        "no_windows",
        "requires-network",
    ],
)

sh_test(
    name = "bazel_with_jdk_test",
    size = "medium",
    srcs = ["bazel_with_jdk_test.sh"],
    args = ["$(JAVABASE)"],
    data = [
        ":test-deps",
        "//src:bazel",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "no_windows",
    ],
    toolchains = ["@rules_java//toolchains:current_java_runtime"],
)

sh_test(
    name = "build_files_test",
    size = "medium",
    srcs = ["build_files_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_bootstrap_distfile_test",
    timeout = "eternal",
    srcs = ["bazel_bootstrap_distfile_test.sh"],
    args = [
        "$(location //:bazel-distfile)",
        "$(location //src:embedded_jdk_allmodules)",
    ],
    data = [
        ":test-deps",
        "//:bazel-distfile",
        "//src:embedded_jdk_allmodules",
        "@bazel_tools//tools/bash/runfiles",
    ],
    exec_compatible_with = ["//:highcpu_machine"],
    tags = ["block-network"],
)

sh_test(
    name = "bazel_bootstrap_distfile_tar_test",
    timeout = "eternal",
    srcs = ["bazel_bootstrap_distfile_test.sh"],
    args = [
        "$(location //:bazel-distfile-tar)",
        "$(location //src:embedded_jdk_allmodules)",
    ],
    data = [
        ":test-deps",
        "//:bazel-distfile-tar",
        "//src:embedded_jdk_allmodules",
        "@bazel_tools//tools/bash/runfiles",
    ],
    exec_compatible_with = ["//:highcpu_machine"],
    tags = [
        "block-network",
        "no_windows",
    ],
)

sh_test(
    name = "bazel_determinism_test",
    timeout = "eternal",
    srcs = ["bazel_determinism_test.sh"],
    args = ["$(location //:bazel-distfile)"],
    data = [
        ":test-deps",
        "//:bazel-distfile",
        "@bazel_tools//tools/bash/runfiles",
    ],
    exec_compatible_with = ["//:highcpu_machine"],
    tags = [
        "no_windows",
        "slow",
    ],
)

sh_test(
    name = "rule_test_test",
    size = "medium",
    srcs = ["rule_test_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 2,
)

sh_test(
    name = "help_test",
    size = "small",
    srcs = ["help_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "java_launcher_test",
    size = "medium",
    srcs = ["java_launcher_test.sh"],
    args = ["$(JAVABASE)"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
    toolchains = ["@rules_java//toolchains:current_java_runtime"],
)

genquery(
    name = "srcs_list",
    expression = "kind(\"source file\", deps(//:srcs))",
    scope = ["//:srcs"],
)

sh_test(
    name = "srcs_test",
    size = "small",
    srcs = ["srcs_test.sh"],
    data = [
        ":srcs_list",
        "@local_bazel_source_list//:sources",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_strategy_test",
    size = "small",
    srcs = ["bazel_strategy_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

test_suite(
    name = "all_tests",
    visibility = ["//visibility:public"],
)

sh_test(
    name = "jdeps_test",
    size = "large",
    srcs = ["jdeps_test.sh"],
    data = [
        ":jdeps_class_denylist.txt",
        ":test-deps",
        "//src:embedded_jdk_allmodules",
        "//src:jdeps_modules.golden",
        "//src/main/java/com/google/devtools/build/lib/bazel:BazelServer_deploy.jar",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "cc_flags_supplier_test",
    size = "medium",
    srcs = ["cc_flags_supplier_test.sh"],
    data = [
        ":test-deps",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "generate_xml_test",
    srcs = ["generate_xml_test.sh"],
    data = [
        "//src/test/shell:bashunit",
        "//tools/test:test_xml_generator",
    ],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "windows_arg_esc_test",
    srcs = ["windows_arg_esc_test.sh"],
    data = [":test-deps"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "bazel_execlog_test",
    srcs = ["bazel_execlog_test.sh"],
    data = [
        ":test-deps",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "new_local_repo_test",
    srcs = ["new_local_repo_test.sh"],
    data = [":test-deps"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "archive_contents_test",
    srcs = ["archive_contents_test.sh"],
    data = [":test-deps"],
    tags = [
        "requires-network",
    ],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "path_mapping_test",
    size = "large",
    srcs = ["path_mapping_test.sh"],
    data = [
        ":test-deps",
        "//src/tools/remote:worker",
        "@local_jdk//:jdk",  # for remote_helpers
    ],
    tags = [
        "no_windows",
        "requires-network",  # for Bzlmod
    ],
    deps = [
        "//src/test/shell/bazel/remote:remote_utils",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "platforms_test",
    srcs = ["platforms_test.sh"],
    data = [":test-deps"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "resource_compiler_toolchain_test",
    srcs = ["resource_compiler_toolchain_test.sh"],
    data = [
        ":test-deps",
        "//src/main/res:srcs_for_testing",
    ],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "unicode_filenames_test",
    srcs = ["unicode_filenames_test.sh"],
    data = [":test-deps"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "run_test",
    srcs = ["run_test.sh"],
    data = [":test-deps"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "unix_digest_hash_attribute_name_test",
    srcs = ["unix_digest_hash_attribute_name_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "verify_workspace",
    srcs = ["verify_workspace.sh"],
    data = [
        ":test-deps",
        "//:workspace-deps-bzl",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "no_windows",
        "requires-network",
    ],
)

sh_test(
    name = "check_external_files_test",
    size = "large",
    srcs = ["check_external_files_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "profile_test",
    size = "small",
    srcs = ["profile_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)
